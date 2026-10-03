# 蓝牙机制：缺陷模式与修复范式

> 学习资料（文章模式沉淀）。本文按五组机制组织车载蓝牙缺陷：连接/回连/扫描操作的互斥与补偿、跨进程状态及单设备事件冒充全局状态、PBAP 协议计数/授权结果/联系人数据一致性、多音源媒体状态及重复输入、并发通话状态与界面同步。源文档：`Summary/project/yadi/git提交与缺陷分析` 00_主报告 §5 模式五、BTPhone.md、BTMusic.md 及逐 commit 详析深案例（缺陷单号 SIR-xxxx 为溯源锚点，代码均节选自真实 diff）。蓝牙扫描与连接的能效视角见 [../14-cpu-power/02-energy-efficiency.md](../14-cpu-power/02-energy-efficiency.md)。防抖吞请求、超时取消纪律、乐观更新确认等异步时序通用范式见 [01-main-thread-async.md](../02-app-framework/09-defect-main-thread-async.md)，占位通话与 Loading 卡死的排查清单见 [03-crash-protection.md](../15-performance/10-app-crash-patterns.md)。本文于 2026-09-26 补充代码级讲解与深案例，并校订中英文间距和术语。

**Q1: 副蓝牙自动回连执行期间，用户点击"刷新"发起设备扫描为什么会立即无响应？正确的打断与补偿方案是什么？（SIR-7390）**

蓝牙协议栈侧的自动回连与扫描（inquiry）是互斥操作：回连执行期间底层会拒绝或直接终结新发起的 inquiry，UI 层如果不处理这一冲突就表现为"点了没反应"，搜索看似启动随即结束。原代码的扫描入口直接下发扫描指令，完全没有"回连占用中"这一冲突分支。

修复为"打断 + 延迟执行"两步，扫描启停入口的真实改法（节选自修复 diff）：

```diff
+            case TYPE_AUTO_CONNECT_INTERRUPT:
+                mOperationState = OperationState.AUTO_CONNECT_INTERRUPT;
+                mBtAdapter.AnWBT_Set_Auto_Connect_Interrupt_Flag();
+                break;
             case TYPE_START_INQUIRY:
-                mBtAdapter.AnWBT_StartInquiry(10, 60, 0);
+                mBtAdapter.AnWBT_Set_Auto_Connect_Interrupt_Flag();
+                ThreadUtils.runOnUiThread(() -> mBtAdapter.AnWBT_StartInquiry(10, 60, 0), 150);
+                break;
```

打断标志经 AIDL 新增接口透传给协议栈，令其退出回连状态。150 ms 延迟给协议栈留出退出回连的时间窗，之后才下发 inquiry。代码仍向厂商扫描接口传入 `10, 60, 0`，其具体参数含义取决于该 vendor API 声明，不能仅凭调用点解释。设备点击连接路径同样先打断再连接，避免同类冲突。

同一 Manager 还可用显式操作状态描述 `IDLE`、`SCANNING`、`PAIRING`、`CONNECTING`、`DISCONNECTING` 和 `UNPAIRING`，并在开始扫描时先置为 `SCANNING`。这些状态名只有在入口守卫和完成/失败回调都维护完整时才构成有效状态机。仅声明枚举或设置状态值不会自动阻止互斥操作。

边界：150 ms 是经验值而非事件驱动，若回连退出耗时超过 150 ms，问题仍可能低概率复现。能拿到协议栈回连结束回调时，应改成等回调再执行。

判断规则：底层操作存在互斥状态机（回连、扫描、配对互斥）时，UI 层发起操作前必须先处理状态冲突，而不是直接下发指令。"打标志 + 延迟执行"是快速止血，事件驱动才是终态。

**Q2: 车机前后排耳机两路蓝牙连接已占满时，点击第三台已配对设备为什么静默无响应？资源满载时的置换连接应该怎么做？（SIR-8556）**

点击处理只覆盖了"已配对/未配对、已连接/未连接"的常规分支，未连接设备直接发起连接。该车机蓝牙最多同时维持两路耳机连接（前排、后排各一路），两路已满时底层因槽位占用导致连接失败。UI 层既没有置换逻辑也没有失败提示，构成"路径未覆盖的分支静默失败"。

修复在点击入口前置"双路已满"判断。未满时走原逻辑，已满时弹出置换弹窗，让用户选择先断开前排还是后排，再按串行时序执行（节选自修复 diff）：

```kotlin
if (mConnectFrontDevice != null && mConnectBackDevice != null) {
    BluetoothConnectHintDialog(mConnectFrontDevice!!, mConnectBackDevice!!,
        object : BluetoothConnectHintDialog.Callback {
            override fun callback(chooseDevice: DeviceBean) {
                BtAnwManager.getInstance().operationBluetooth(OperationType.TYPE_AUTO_CONNECT_INTERRUPT)
                delay(150.milliseconds)          // …（节选自真实 diff）
                disconnectDevice(chooseDevice)
                delay(500.milliseconds)
                BtAnwManager.getInstance().connectHfp(device, true)
                BtAnwManager.getInstance().connectA2dp(device, true)
            }
        }).show(childFragmentManager, "BluetoothInfoDialog")
```

时序为"中断自动连接 → 延时 150 ms → 断开被选设备 → 延时 500 ms → 对新设备发起 HFP 与 A2DP 连接"。界面先给出置换选择，避免原先静默无响应。是否成功建立新连接仍须等待 profile 状态回调确认。

代码中的 `delay(150.milliseconds)` 和 `delay(500.milliseconds)` 分别等待 150 ms 与 500 ms。`connectHfp(device, true)` 和 `connectA2dp(device, true)` 的布尔参数含义取决于项目方法签名，应核对定义，不能只从调用点推测其策略。

边界与状态维护要求如下：

1. **固定等待的限制：**断开与重连之间的固定延时属于经验时序，极端蓝牙栈延迟下置换可能不彻底。弹窗只有在用户选择后才允许确认，确保回调拿到要断开的设备。
2. **槽位状态：**前排、后排设备引用要在列表刷新和蓝牙关闭时同步维护或清空，否则“是否已满”的判断依据会过期。
3. **派生状态：**重建已配对设备列表时，要合并旧条目上的 HFP、A2DP 和前后排角色状态。只替换列表会把这些状态清零，造成后续连接守卫误判。
4. **资源置换：**枚举连接槽位和并发上限，并为资源满载提供显式处理或提示。让用户选择断开哪个设备，比静默断开最早连接设备更可控。

**Q3: 切换已配对蓝牙设备时，"先 disconnect 旧设备、固定延时 160ms 再 connect 新设备"为什么经常要反复切换才能成功？（SIR-3261）**

断开是异步过程，固定延时不能保证协议栈已完成断链。未断完就发起 connect 时，底层会因已有连接占用而拒绝。这是"未等其他设备断开连接就去连接"的典型时序竞态，用户只能反复切换碰运气。

修复把连接动作改成事件驱动：存在其他在线设备时不再延时直连，而是先挂回调再断开（节选自修复 diff）：

```kotlin
if (mWxBtManager.currentConnectDevice != null && mWxBtManager.currentConnectDevice != device) {
    BluetoothFragment.SConnectCallback = object : BluetoothFragment.OnConnectListener {
        override fun onConnectDevice() {
            device.connect(true)          // …（节选自真实 diff）
        }
    }
    mWxBtManager.currentConnectDevice.disconnect()
} else {
    device.connect(true)
}
```

触发侧在蓝牙状态回调的 `STATE_DISCONNECTED` 分支调用 `SConnectCallback?.onConnectDevice()` 并随即置空——等断开真正完成才执行新设备的 connect，从机制上消除对固定延时的依赖。配套地，回调挂在静态字段上必须成对管理：页面初始化与销毁时清空，避免断开广播一直不来时连接被无限搁置，或跨页面残留触发错误设备的连接。

判断规则：用固定 sleep/delay 等待异步蓝牙状态变更不可靠，应订阅真实状态事件后再发起下一步动作。遇到"多次重试才成功"，优先检查异步完成事件与后续动作的衔接，再排除权限或参数问题。

**Q4: 点击已配对蓝牙设备发起连接时，哪些代码形态会把用户的点击"静默吞掉"？连接入口应该如何设计兜底？（SIR-1435）**

两种形态会共同吞掉操作。设备上报的能力值为 `0`、`-1`、`1` 这类"未知"取值时，代码直接早退且不做任何处理。设备类型匹配后又附加位掩码强校验，也会在类型已知时挡住连接请求。

刚配对完成时协议栈状态未稳，这些能力值可能暂时不可靠。原拦截分支只写 debug 日志，没有连接动作或用户反馈，因此点击表现为"无响应"。

修复删掉两道静默拦截并补兜底路径（节选自修复 diff）：

```diff
-                if (profileSupport == 0 || profileSupport == -1 || profileSupport == 1) {
-                    Log.d(TAG, "don't know device support " + config.name);
-                } else if (containsInt(config.sinkDevTypes, devType) && (profileSupport & config.sinkBitmask) != 0) {
+                if (containsInt(config.sinkDevTypes, devType)) {
                     mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sinkProfile);
+                } else if (containsInt(config.sourceDevTypes, devType)) {
+                    mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.sourceProfile);
                 } else {
-                    Log.d(TAG, "device doesn't support " + config.name);
+                    // …（节选自真实 diff）
+                    mBtAdapter.AnWBT_Connect_Service(device.macAddress, config.fallbackProfile);
                 }
```

现在设备类型命中即发起对应 profile 连接。类型完全识别不出时也会尝试兜底 profile，而不是静默早退。兜底尝试不保证连接成功，失败仍须由底层回执传回并在界面呈现。

边界：对识别不出的设备一律走兜底连接，可能对确实不支持的设备发起无效尝试，因此依赖底层失败回执兜底。

判断规则：排查"点击无响应"时先查分支是否只有 Log、没有 action。用户操作应有兜底路径，识别不出不应等于不执行。

**Q5: 蓝牙开关状态保存在 UI 层静态变量（如 Constant.BT_ON）会造成什么失步？状态应该收敛到哪里？（SIR-1003）**

开关状态被冗余保存在 UI 层静态字段，Fragment 初始化、状态回调、数据刷新多处分别读写它：任何一处漏更新，或 Fragment 重建后用过期值回写控件状态，都会造成"界面认为已开、底层认为已关"的错位，后续点击被状态守卫逻辑吞掉，表现为开关点击失效。

修复把开关状态的唯一事实源迁入蓝牙管理类，UI 只通过 Manager 读写该状态（节选自修复 diff）：

```java
-    public List<DeviceBean> mPairedDevices = new ArrayList<>();
+    private boolean mBtOn = false;
+    public void setBtOn(boolean btOn) { mBtOn = btOn; }
+    public boolean isBtOn() { return mBtOn; }
```

所有开关读写统一走 `BtAnwManager` 的 `isBtOn()/setBtOn()`，UI 不再维护第二份可写状态。单一事实源解决的是状态归属，不自动解决并发可见性：如果 Binder 回调线程与 UI 线程会并发访问该布尔值，还要通过线程切换、同步或合适的可见性机制保护读写。

判断规则：蓝牙开关等硬件/服务状态应由 Manager 持有唯一事实源，并提供统一读取入口。UI 展示该状态，不要用静态字段缓存并反向覆盖服务状态。

**Q6: 反复开关蓝牙后，开关"显示关闭、再点击无法开启"的死锁是怎样形成的？（SIR-8679）**

开关的覆盖层防抖机制是：开启覆盖层时禁用控件本体并把点击转给覆盖层监听，只有显式解除覆盖层才恢复可点。关闭蓝牙的确认路径把开关置为 OFF、开启覆盖层并置半透明，却没有后续代码解除禁用。此时点击仍进入覆盖层监听，又被"开关已关闭直接返回"的守卫吞掉。控件本体处于 disabled，覆盖层、禁用态和守卫叠加形成死锁。

修复把开关做成显式三态：半透明（过渡中，点击一律忽略）、完全不透明（已开启待操作，覆盖层点击弹关闭确认）、解除覆盖（已关闭可点击），过渡态守卫与恢复路径的真实改法（节选自修复 diff）：

```kotlin
mBindingHeader.sw.setOnOverlayClickListener {
    if (!mBindingHeader.sw.isChecked ||
        mBindingHeader.sw.switchCompat.alpha != 1.0f) return@setOnOverlayClickListener
    // …（节选自真实 diff）
}
ManagerConstants.STATE_ON -> {
    mBindingHeader.sw.enableOverlay(1.0f)   // 开启成功：恢复完全不透明、可操作
}
```

配套把 `enableOverlay(boolean)` 重构为 `enableOverlay(alpha: Float)`——"遮罩"与"置暗"原是两个正交概念，揉进一个布尔参数正是状态错位的温床。开启成功显式恢复完全不透明，过渡态的点击一律忽略直到状态落定，蓝牙 OFF 分支走 `disableOverlay()` 恢复可点，"关闭后锁死"的路径消失。

边界：拿控件透明度当逻辑状态位属于约定式状态机，新代码路径漏设透明度就会复发。更稳妥的做法是用独立布尔状态表示过渡阶段。

判断规则：评审"覆盖层 + 禁用控件"设计时，逐路径确认谁来解除禁用。复现反复或被打断的操作后，检查透明度、enabled 和覆盖层可见性，找出未恢复的状态。

**Q7: 前后排蓝牙耳机同时连接时来电双方无声，为什么不能等协议栈自己恢复？连接失败的重试逻辑应该怎么设计？（SIR-4601）**

两台 HFP 设备并存是协议栈连接不稳定的典型诱因：HFP 偶发连接失败后无人再发起连接，音频网关一直不就绪，来电即双方无声。原状态机只处理了连接成功与断开两个分支，对"连接失败"状态没有任何处置——失败即终态，缺少补偿机制，应用层等待不会带来自愈。

修复新增失败分支：仅对主角色（前排，role=1）设备调度重试，参数遵循"三件套"——最多重试 2 次、间隔 2 秒、距首次失败超过 60 秒重置计数窗口，防止无限风暴（节选自修复 diff）：

```java
+    private static final int HFP_RETRY_MAX_COUNT = 2;
+    private static final long HFP_RETRY_DELAY_MS = 2000L;
+    private static final long HFP_RETRY_RESET_WINDOW_MS = 60_000L;
+    private final Map<String, Integer> mHfpRetryCount = new ConcurrentHashMap<>();
 } else if (state == BtAdapterMessage.CONNECT_STATE.STATE_CONNECT_FAILED) {
+    mPairedDevices.stream().filter(it -> it.macAddress.equals(address)).findFirst().ifPresent(it -> {
+        if (it.role == 1) {
+            scheduleHfpRetry(it);   // 2s 后 connectHfp；超上限或超 60s 窗口即放弃
+        }
+    });
 }
```

重试任务按设备 mac 地址隔离（`postDelayed` 以 mac 为 token，防同设备任务重叠），计数用并发容器存放。连接成功或断开时同步清理计数与任务，否则计数泄漏会让后续重试失效。

边界：2 秒延迟窗口内用户可能已主动断开或切换设备，因此重试与用户操作仍有竞态，需在断开事件中清理待执行任务。协议栈层的不稳定要与底层共同排查，应用层重试只是兜底。

这类重试应限制次数与间隔，设置计数重置窗口，按设备隔离任务，并使用合适的并发容器管理计数。

**Q8: 已配对设备重新出现在可配对扫描列表里，为什么排查方向应是"配对调用次数"而不是"列表过滤"？（SIR-5800）**

配对变体处理函数按系统枚举分发应答（PIN、passkey、配对确认、OOB 各走各的分支），但 switch 结束后又无条件补发了一次"自动确认"：对确认类变体等于连续确认两次，对其他变体也注入了一次非预期应答。示例中的 `2`、`3`、`6` 是该代码分支的数值变体标识，解释时应以目标 Android/vendor 分支对应的符号常量为准，不能脱离版本把数字当作稳定语义。重复应答使协议栈为同一设备重走配对流程、生成新的配对记录，已连接设备因此再次混入可配对列表。

修复把这段公共确认收敛为 switch 的 default 分支（节选自修复 diff）：

```diff
-            case 2:
-            case 3:
-                device.setPairingConfirmation(true);
-                break;
             case 6:
                 setRemoteOutOfBandData(device);
                 break;
+            default:
+                device.setPairingConfirmation(true);
         }
-
-
-        device.setPairingConfirmation(true);
```

显式处理过的变体不再附加自动确认，只有未覆盖的变体走 default 应答一次。每种变体只执行一次应有的响应后，重复配对消失。按 Android `BluetoothDevice` 配对变体常量，`2` 是 passkey confirmation，`3` 是 consent，`6` 是 OOB consent。vendor 分支仍应确认这些数字对应同一套常量。

这个案例的通用形态是"switch 后再补一段兜底公共操作"极易与分支内已做的操作重复。同一动作在 case 和 switch 之后各执行一次，就会造成双发。公共动作要么收敛进 default，要么删除 case 内的重复副本。

边界：default 自动确认意味着未来新增的配对变体也可能被静默确认，需随系统枚举扩展重新审查。

判断规则：多余的"保险式"协议应答会带来协议层副作用。已配对设备重现扫描列表时，应先检查配对调用次数，再检查列表过滤。

**Q9: 界面 Fragment 在销毁时注销了单例蓝牙 Manager 的公共广播接收器，会造成什么后果？注销范围应如何对齐生命周期？（SIR-6206）**

蓝牙管理类是跨界面共享的单例，其广播接收器负责扫描、配对、绑定状态更新的整条链路。某个连接界面销毁时把这个接收器整体注销，设备从"可配对"迁入"已配对"的状态迁移事件从此无人处理，残留的中间态数据（同一设备既留在扫描结果又进入绑定列表）在下次进入界面时原样展示，形成两个列表同时显示同一设备。

修复只保留"注销自己的回调"这一步，不再注销 Manager 的公共接收器（节选自修复 diff）：

```kotlin
override fun onDestroy() {
    BtAnwManager.getInstance().setIsInitStatus(false)
    BtAnwManager.getInstance().unregisterCallback(this)
-        BtAnwManager.getInstance().unregisterReceiver()
+//        BtAnwManager.getInstance().unregisterReceiver()
    mScanJob?.cancel()
    mRoleUpdateTimeoutJob?.cancel()
```

界面销毁后事件链路保持畅通，单例中的数据能被后续广播正常刷新与去重。注销范围应与对象生命周期对齐：短生命周期界面只注销自己注册的回调，不替长生命周期 Manager 注销公共接收器。界面回调还须检查 Fragment 是否仍存活，角色切换等等待回调的操作应有超时收尾，避免回调丢失后界面永久无响应。

如果 Manager 的设备列表由 Binder 回调线程更新、UI 线程同时遍历，可用 `CopyOnWriteArrayList` 避免迭代期间的结构修改异常。它的迭代器读取一个快照，写入需要复制底层数组，因此更适合读多写少的列表。它不会让“列表内容与设备状态标志”这样的多字段更新自动成为原子操作。

判断规则：监听器注册/注销与共享集合访问都要按实际对象生命周期和并发模型设计，不能只因容器线程安全就假设整个设备状态一致。

边界：公共接收器常驻需要在 Manager 层面有全局注销时机，否则可能内存泄漏。若代码库仍保留注释掉的注销调用，应在注释中说明原因，避免后续清理时恢复旧调用并引入回归。

判断规则：列表重复显示时，优先检查状态迁移事件链是否在某个生命周期节点被掐断。

**Q10: 蓝牙管理类单例在 A 进程初始化、B 进程调用 getInstance()，为什么拿到的是"能编译但行为错误"的空副本？跨进程共享这类状态的正确姿势是什么？（SIR-2463，提交 aa9f9d8d）**

单例的"全局"只在本进程内成立。该蓝牙管理类的配对设备列表等状态在 Setting 进程中初始化和维护。蓝牙音乐进程通过编译期依赖调用它的 AVRCP 控制方法时，拿到的是自己进程里一份全新实例，配对列表为空，遍历空列表导致一条 AVRCP 命令都发不出去——编译能通过不代表运行正确，"修好了"必须验证命令真的到达对端。

修复改用 `Settings.Global` 键传递单个控制值：发送侧写入约定键，持有真实蓝牙状态的进程用 ContentObserver 读取后执行控制（节选自修复 diff）：

```kotlin
-            BtAnwManager.getInstance().setAvrcpControl(if (isPlaying) 0 else 2)
+            SettingsUtils.setGSetting("custom_avrcp_event", if (isPlaying) 0 else 2)
+    class GlobalSettingsObserver(handler: Handler, private val context: Context, private val key: String)
+        : ContentObserver(handler) {
+        override fun onChange(selfChange: Boolean, uri: Uri?) {
+            val value = Settings.Global.getInt(context.contentResolver, key, 2)
+            BtAnwManager.getInstance().setAvrcpControl(value)
+        }
+    }
+// …（节选自真实 diff，注册/反注册略）
```

命令执行点从"空状态的单例副本"搬回"持有配对列表的真实单例"。蓝牙音乐进程同时撤掉对硬件库的编译依赖。断开蓝牙时补写暂停控制值 `2`，在该项目约定中它表示暂停，确保断连路径也下发该控制值。读取代码里的默认值 `2` 也属于这个项目协议，不能解释成 Android `Settings.Global` 的通用默认含义。

发送代码中的 `0` 与 `2` 是该项目定义的 AVRCP 控制值，`Settings.Global.getInt(..., 2)` 的末尾 `2` 则是键不存在时使用的读取默认值。它们恰好相同，不代表 Android 平台赋予这两个整数通用播放/暂停含义。应以 `setAvrcpControl()` 和发送侧约定为准。

这个实现是特定系统应用中的单值通知方案，不是可靠消息队列。`Settings.Global` 位于全局命名空间，键名需要项目归属前缀，写入还受系统权限约束。ContentObserver 未注册时的变更不会排队，连续写相同值也不能被当作每次都可观察到的独立事件。因此可能丢失启动前或快速连续发出的控制请求。

跨进程调用优先使用有明确接口和生命周期的 AIDL、受控广播或服务。只有系统组件明确需要共享单个状态值、并能接受丢失或合并通知时，才考虑使用系统设置作为信箱。不能把进程内单例当作跨进程共享对象。

**Q11: 多设备蓝牙场景下，把 HFP 连接状态广播里的 EXTRA_STATE 直接当全局连接状态，为什么会误判？"事件触发 + 状态回查"的正确结构是什么？（SIR-8400，提交 781a6000）**

`ACTION_CONNECTION_STATE_CHANGED` 是按设备投递的"单台设备事件"。手机互联设备断开时，其 HFP 断连广播被无条件写入全局状态，仍然连接的手机被一并判为断开，界面随之显示"未连接设备"——单设备事件冒充了聚合状态。

修复把状态源从"转发广播 EXTRA_STATE"改为"收到通知后回查聚合事实"（节选自修复 diff）：

```java
// UiBluetoothMonitor.java：广播回调只做触发，状态回查权威数据源
+    public int getHfpClientConnectionState() {
+        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
+            return BluetoothProfile.STATE_DISCONNECTED;
+        }
+        return mBluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET_CLIENT);
+    }
+    @Override
+    public void onStateChanged() {   // 收到通知后回查，不再转发 EXTRA_STATE
+        updateBluetoothConnectionState(UiBluetoothMonitor.get().getHfpClientConnectionState());
+    }
```

广播中的设备不是当前关注设备、或回查结果显示仍在连接时，不再误报。Profile 服务就绪后主动 `notifyListeners()` 刷新初始状态，保证冷启动首发状态也正确。`BluetoothProfile.HEADSET_CLIENT` 是目标 AOSP 分支的 HFP Client profile 常量，对普通第三方应用并非稳定公开 SDK 契约，它替代了代码里难以审读的数字 `16`。配套地删掉了中间的双层 Provider 转发，状态链路缩短，消除了两套监听不一致的隐患。顺带修正了一个侥幸工作的常量错配——用蓝牙开关态的"已连接"常量与 Profile 状态比较，数值恰好相同才没出错，应改用语义正确的 Profile 状态常量。

边界：每次通知都同步回查是一次 Binder 调用，频率不高时可接受。判断规则：事件与状态必须分层——事件只做触发，真实状态回查权威数据源。判断"是否有设备连接"必须用聚合查询，或先核对广播中的设备是否为当前关注设备。

**Q12: PBAP 联系人拉取分"listing 拿数量"和"pullPhonebook 拉数据"两步，为什么第二步不能重新查询"当前主设备"？（SIR-8209）**

两步之间隔着一次异步回调，第二步重新查询当前活跃 HFP 设备，在设备切换、后授权等场景会拿到不同设备甚至 null：为 null 时静默返回且不置失败状态，界面 Loading 永远等不到结束事件。取到旧设备则拉取必然失败。两步用的设备来源不一致，本身就是竞态入口。

修复把数量回调广播携带的设备一路透传给拉取方法，并给所有失败分支补上失败状态（节选自修复 diff）：

```diff
-                            startPullPhoneBook(requestedContacts);
+                            startPullPhoneBook(device, requestedContacts);
-        BluetoothDevice currentDevice = UiBluetoothMonitor.get().getMainDevice();
-        if (currentDevice == null) {
+        // 使用 listing 结果广播携带的设备，保证数量与拉取属于同一次 PBAP 请求
+        if (device == null) {
             LogUtils.w(TAG, "Cannot pull contacts because broadcast device is unavailable");
             endContactsDownload();
+            mDownloadStates.setPbDownloadState(DownloadStates.STATE_DOWNLOAD_FAIL);
             return;
```

数量统计与数据拉取由此绑定到同一次 PBAP 会话、同一台设备。客户端为空、设备为空、拉取失败等提前返回分支统一置失败并同步给仓库层，并且去掉"仅界面处于 Loading 态才同步失败状态"的前置条件——数据层行为不应依赖视图层状态。

通用规则：跨回调的两步操作必须把上下文（设备、会话标识）显式透传，在消费端重新查询"当前状态"就是竞态。提前 return 的失败分支必须同步置失败状态并通知 UI，静默 return 是 Loading 永挂的常见来源。

**Q13: iPhone 未授权联系人同步时，界面为什么可能一直显示进度条和"同步完成"？协议层进度与业务层结果应该如何分层？（SIR-5889）**

未授权时 iPhone 的 PBAP 也会回报 100% 传输结束。界面层有一段特判："同步中收到 100% 就强制把 UI 覆盖成同步完成"，直接把未授权页盖掉。而 ViewModel 里又有一条守卫："同步必需态收到空列表就保持现状直接返回"。两段防御叠加，Empty 与未授权状态永远发布不出来，界面锁死在进度条加假"同步完成"。

修复把状态裁决权收归 ViewModel：界面收到 100% 只记日志，不再动 UI（节选自修复 diff）：

```diff
-            binding.tvSyncProgress.setText("同步完成");
-            binding.progressBarSync.setProgress(100);
-            binding.tvSyncProgress.postDelayed(() -> { ... }, 500);
+            // 100%只代表PBAP传输结束，最终显示由ViewModel根据联系人数据决定
+            LogUtils.i(mTAG, "Received 100% progress, wait for Success or Empty");
             return;
```

ViewModel 里"空数据保持现状"的守卫一并删除，未授权场景的空列表能走完状态发布链路，切换到未授权文案页。授权成功的判定改为"实际同步数据与授权状态组合判断"——只有拿到非空数据才算授权成功。

这次修复遵循三条状态处理规则：

1. **分离传输进度与业务结果：**PBAP 的 100% 表示传输阶段结束，不单独证明已授权或取得有效联系人。最终界面应依据授权状态和数据结果确定。
2. **让守卫依据可区分状态：**不能把空列表一律当作“尚未完成”，因为它也可能表示未授权或有效空结果。状态机应使用明确的授权和同步状态区分这些情况。
3. **由单一状态源驱动渲染：**界面收到进度时更新进度展示，不应通过 `postDelayed` 猜测业务状态何时改变。

**Q14: 通讯录同步完成显示 4999 条而不是 5000 条，为什么"差一条"不一定是边界问题？（SIR-5977）**

真正原因在数据转换环节：同一份 PBAP 快照列表被两个联系人仓库共享，且各自在内部修改列表元素。转换在 IO 线程执行时与其他线程的访问发生并发修改异常，转换一旦抛异常该条联系人即被丢弃，最终少一条。表面是数量边界，实际是异常吞数据——先看异常日志再下"边界值"结论。

修复对每个联系人用 Parcel 序列化做完整深拷贝，两个仓库各持独立副本（节选自修复 diff）：

```java
+    private static List<BluetoothPbapContact> deepCopyContacts(List<BluetoothPbapContact> contacts) {
+        List<BluetoothPbapContact> copies = new ArrayList<>(contacts.size());
+        for (BluetoothPbapContact contact : contacts) {
+            Parcel parcel = Parcel.obtain();
+            try {
+                contact.writeToParcel(parcel, 0);
+                parcel.setDataPosition(0);
+                copies.add(BluetoothPbapContact.CREATOR.createFromParcel(parcel));
+            } finally { parcel.recycle(); }
+        }
+        return copies;
+    }
```

（null 元素分支略。）两个仓库拿到的是互相独立的副本，任一仓库内部修改不再影响另一个，消除了该缺陷中共享集合导致的并发修改异常。Parcel 深拷贝成立的前提是 Parcelable 实现完整复制所需字段，且复制期间源对象本身没有被并发修改。它是此项目采用的修复方式，不是所有对象的默认最佳做法。

代码中的 `Parcel.obtain()` 取得可复用 Parcel，`writeToParcel(..., 0)` 以无特殊写入标记的方式序列化对象，`setDataPosition(0)` 将读取游标复位后由 `CREATOR` 重建对象，最后在 `finally` 中调用 `recycle()` 归还 Parcel。`0` 是 Parcelable flags 参数，不是联系人字段或数量。

边界与代价：对 5000 条联系人逐条 Parcel 深拷贝会增加序列化耗时和临时内存占用。原案例认为在目标车机上可接受，但必须在目标设备测量。此改动没有解除协议/产品侧的 5000 条联系人上限。

共享数据的正确性要从所有权、复制方式和执行时序一起判断：

1. **明确快照所有权：**多个仓库共享可变对象时，任何消费者都可能修改它，不能把只读约定当作线程安全保证。
2. **确认复制语义：**Parcel 复制要求 Parcelable 实现完整，并且复制期间源对象稳定。若不满足，应使用受控快照或按字段复制。
3. **区分线程调度与线程安全：**切到 IO 线程不会阻止其他线程同时改写数据。应串行化读写或发布不可变副本。

**Q15: PBAP 把本机号码占位条目计入 vCard 请求时，为什么只有一位联系人的同步会卡住？**

PBAP 的 phonebook 条目数可能包含 `0.vcf` 本机号码占位条目，而应用展示的联系人总数不包含它。若把协议数量直接作为有效联系人数量，同时又按有效数量请求 vCard，只有一条联系人时就可能少拉一条，完成计数无法满足。

修复把“有效联系人数量”和“协议请求数量”分开计算：

```diff
-                        int requestedContacts = size > CONTACTS_COUNT_LIMIT
-                                ? MAX_CONTACTS_COUNT : size;
-                        totalContacts = requestedContacts;
+                        totalContacts = Math.min(size, CONTACTS_COUNT_LIMIT);
+                        int requestedContacts = totalContacts > 0 ? totalContacts + 1 : 0;
                         if (result == 0) {
-                            // 协议数量超过5000时使用5001作为边界值，否则使用协议实际数量。
+                            // 0.vcf 为本机号码：非空通讯录请求数需在协议联系人数量上加 1
```

`size` 是协议报告的条目数，`CONTACTS_COUNT_LIMIT` 把应用接受的有效联系人数量限制在 5000 条。代码把有效数量保存到 `totalContacts`，仅在该数量大于 0 时把请求数加 1，以覆盖 `0.vcf` 占位条目。这个补偿假设目标 PBAP 设备确实采用该占位约定，不能推广成所有 PBAP 实现的固定规则。

**Q16: PBAP 联系人只有姓名、没有电话号码时，为什么同步后会变成空白？转换规则应如何处理？**

问题出在应用把“没有号码”误当成“不是联系人”，转换时丢弃了整条记录。姓名有效的联系人仍应进入联系人列表，号码字段为空则由拨号等下游功能自行处理。

1. **保留联系人记录：**转换只过滤 null 联系人对象，不要因为电话号码集合为空就删除有姓名的联系人。
2. **保留不完整字段：**号码快照或号码转换失败时记录诊断信息，但不要因此丢弃姓名和其他有效字段。
3. **约束下游操作：**联系人列表、搜索与排序应允许号码为空。拨号入口在使用号码前再检查空值，并给出合适的不可拨号行为。

旧实现把两种失败混为一谈：没有可拨号码不表示 PBAP 没有联系人。通讯录转换应在单一入口定义过滤规则，避免列表、收藏或最近通话各自转换出不同结果。

**Q17: 联系人有多个号码时按号码搜索只能命中第一个，这类"取 first"式访问的正确写法是什么？（SIR-8321）**

搜索匹配对每个联系人只取号码列表的第一个元素参与比较，用户输入第二个、第三个号码时自然匹配失败，结果列表为空。多号码联系人在 PBAP 同步的通讯录中很常见（手机号、工作号并存），因此这不是边缘数据，而是真实场景。

修复把取号码的方法改为带输入串的遍历匹配（节选自修复 diff）：

```diff
-    private static ContactData.PhoneItem getItem(ContactData contact) {
+    private static ContactData.PhoneItem getItem(ContactData contact, String numberStr) {
-        ContactData.PhoneItem firstPhone = phoneList.get(0);
+        ContactData.PhoneItem firstPhone = null;
+        for (ContactData.PhoneItem phone : phoneList) {
+            if (phone == null || phone.getNumber() == null) { continue; }
+            if (firstPhone == null) { firstPhone = phone; }
+            if (!TextUtils.isEmpty(numberStr) && phone.getNumber().contains(numberStr)) return phone;
+        }
         return firstPhone;
```

任一号码包含输入串即返回命中的那个号码项，搜索结果展示也使用命中号码，避免"搜中 B 号码、展示 A 号码"的错位。若输入串为空或没有号码命中，方法回退返回第一个有效号码。若没有有效号码则返回 null。这个回退保持原有无号码搜索的展示策略，但空列表和 null 元素仍须由调用方正确处理。`contains()` 是子串匹配，不会自动规范化空格、国家码或标点，号码格式不同仍需单独归一化。

判断规则：多值字段（多号码、多邮箱）上做匹配必须遍历全集，"取 first"式访问必然漏数据。匹配与展示要使用同一个命中项，而不是各自取值。

**Q18: 阿拉伯文联系人姓名和电话号码拼在同一段文本里，为什么号码会被反向显示？拼串时应该怎么处理？（SIR-6370）**

Android TextView 的双向文本（Bidi）算法遇到强 RTL 字符（如阿拉伯文）会以它判定段落方向。姓名与号码同处一个文本流时，紧随其后的 LTR 号码被卷入 RTL 段参与重排，数字顺序呈现反向。这是渲染方向问题，不是数据错误。

修复在拼串时用 Unicode 方向隔离符把号码包成独立隔离区（节选自修复 diff）：

```java
+        CharSequence displayNumber = TextUtils.concat("\u2066", phoneNumber, "\u2069");
         if (fullWidth <= availableWidth) {
-            setText(fullName + " " + phoneNumber);
+            setText(TextUtils.concat(fullName, " ", displayNumber));
             return;
         }
         String ellipsizedName = ellipsizeName(paint, availableWidth - numberWidth);
-            setText(ellipsizedName + " " + phoneNumber);
+            setText(TextUtils.concat(ellipsizedName, " ", displayNumber));
```

前缀 LRI（U+2066）与后缀 PDI（U+2069）让 Bidi 算法在隔离区内强制按 LTR 布局，隔离边界同时切断号码与前面阿拉伯文姓名之间的方向"传染"——姓名保持上下文的 RTL 显示，号码固定 LTR。两个控制字符零宽不可见，对宽度测量与省略号计算基本无影响。

混排文本应在文本构造层解决方向边界：

1. **隔离完整逻辑单元：**在电话号码两侧使用方向隔离符，或使用 `BidiFormatter.unicodeWrap`，让姓名仍服从上下文方向而号码按 LTR 展示。
2. **在拼接处处理：**把隔离符放在电话号码边界，而不是包住整段姓名与号码，避免破坏姓名原有方向。
3. **区分文本与布局配置：**代码拼接后交给 `setText` 的混合文本不能仅靠某个 View 的文字方向属性保证号码独立按 LTR 排列。

**Q19: 两份联系人仓库并存、PBAP 下载完成后双写，会酿成什么结构性问题？修复为什么是合并仓库而不是补双写一致性？（SIR-7487，提交 7b7efe27）**

项目里同时存在旧版联系人仓库（千行级）与较新的"简化版"仓库：通话姓名查询与联系人页面各自从不同仓库取数，PBAP 下载完成后向两个仓库分别写入。双写只要有一路失败或时序滞后，两份数据就漂移——页面甚至通过反射读取另一个仓库的私有字段做兜底，姓名查询查到空或过期的那份，表现为通话界面"只显示号码不显示姓名"。

修复是结构性的：把简化版仓库扩为唯一仓库，数据改为不可变快照加版本号，发布后不再修改内部集合（节选自修复 diff）：

```java
-    private List<BluetoothPbapContact> mPbapContacts = new ArrayList<>();
+    // 快照归仓库独占，发布后不再修改内部集合；清理和更新均替换整份快照。
+    private volatile List<BluetoothPbapContact> mPbapContacts = Collections.emptyList();
+    private final AtomicLong dataVersion = new AtomicLong();
+    public synchronized List<ContactData> getContacts(boolean forceLoad) {
+        mContactList = Collections.unmodifiableList(new ArrayList<>(contacts));
+        return mContactList;
+    }
```

`volatile` 让 `mPbapContacts` 引用的替换对其他线程可见，`AtomicLong()` 建立从 0 开始的原子版本计数，`synchronized` 串行化该方法的并发调用。这些做法分别处理可见性、版本编号和方法互斥，不能彼此替代。整文件删除旧仓库（千行级），全局 13 处引用全部切换。下载引入"完成广播与数据回调乱序"的代际校验加超时兜底，保证"下载完成"状态只在数据真正落库后发出。界面里那段反射读私有字段的兜底代码一并删除。

通用规则："同一份数据两个仓库"是名字显示类 bug 的结构性根源，任何一路写失败都会漂移，正解是合并成单一事实源，而不是继续补双写一致性。用反射读别的类的私有字段是数据归属设计已经出错的信号，重构时应把这类 hack 一并删除。`Collections.unmodifiableList()` 只禁止通过返回的包装视图修改列表，本身不复制列表。示例先构造 `new ArrayList<>(contacts)`，才让已发布快照不受原集合后续增删影响。若列表元素本身可变，还需复制元素或约定元素不可变。边界：千行级删除重构必须全局搜索确认旧类零残留，并回归通讯录、收藏、最近通话、模糊检索全链路，防止被删仓库中未被发现的功能回退。

**Q20: 蓝牙重连后通讯录重新同步，为什么同步完成前来电的弹窗永远拿不到姓名？数据到达后还差哪一步？（VIR-36）**

重连后 PBAP 通讯录需要重新同步，同步完成前姓名无数据源可查，弹窗只能显示号码。而同步完成的代码只刷新了联系人列表本身，从不回头补刷"进行中通话"这个已渲染的消费者——迟到数据永远到不了弹窗。

修复在同步完成的收尾处新增回扫：遍历当前通话列表，号码先保留数字和 `+` 并去掉空格、括号等分隔符，再以至少 7 位的后缀匹配新同步缓存中的号码（用于兼容部分区号差异），然后反查姓名（节选自修复 diff）：

```java
+    private void refreshOngoingCallContactNames() {
+        List<UiCall> calls = UiCallManager.get().getCalls();
+        for (UiCall call : calls) {
+            String normalizedCallNumber = call.getNumber().replaceAll("[^0-9+]", "");
+            String matchedName = findContactNameByNumber(normalizedCallNumber);
+            if (matchedName != null && !matchedName.equals(call.getContactName())) {
+                call.setContactName(matchedName);
+                anyUpdated = true;
+            }
+        }
+        if (anyUpdated) { RxBus.getInstance().post(new RxEventMsg<>(Constants.EventCode.UPDATE_CALLS, null)); }
+    }
```

命中则回写通话对象并发送"通话更新"事件，浮窗订阅该事件后重渲染。"重连 → 来电 → 同步完成"的时序里，姓名会迟到但不缺席。

通用规则：依赖异步数据源的界面，数据到达后要回扫所有已渲染的视图状态，不能只刷新列表本体——进行中的通话、置顶卡片都是易漏的消费者。用事件总线解耦"数据就绪"与"界面刷新"，比各界面自己轮询同步状态干净。边界：后缀匹配在极端相似的号码之间可能误匹配。回扫路径中获取当前通话未判空有崩溃风险，需补防护。

**Q21: 用户取消联系人授权后再重新授权，收藏和最近通话为什么不显示也恢复不了？"缓存式状态成员"的更新纪律是什么？（SIR-2203、SIR-2872）**

页面用成员变量缓存当前 UI 状态，供页面恢复时做分支判断，但状态订阅回调里只渲染 UI、不回写这个缓存——判断永远基于过期状态。叠加第二个缺陷：PBAP 下载失败时仓库不发任何事件，状态机没有可消费的迁移信号，"等待授权"标志永不清除。取消授权后页面停在等待态，重新授权也触发不了同步，两条路都进不来。

修复三处：

1. 状态订阅回调里同步回写缓存成员，让页面恢复的分支判断基于最新状态。
2. 空数据状态不再被当作"加载完成"直接渲染，授权正常时主动触发同步，重授权后数据能拉回来。
3. 下载失败也发送空列表事件，让状态机能迁移到"需要同步"状态，等待标志得以解除。

第 1、3 处的真实改法（节选自修复 diff）：

```diff
         viewModel.getUiState().observe(getViewLifecycleOwner(), state -> {
+            this.currentState = state;
             if (!isFragmentVisible()) {
```

```java
 // ContactsRepository：PBAP 下载失败分支（节选自真实 diff）
+            mContacts.postValue(new ArrayList<>());   // 失败也发事件，状态机才能迁移
```

通用规则：缓存式状态成员要么删掉、每次现读唯一数据源，要么在唯一数据源的回调里同步缓存，绝不允许"只在页面恢复时读、不在数据到达时写"。异步状态机卡死的常见根因是"失败路径不发事件"——失败、取消这类终态也必须发事件。判断规则：授权被撤销再恢复是蓝牙电话的必测往返场景，授权态、同步态、UI 态三者都要能在重授权时回到初始链路。

**Q22: 手机侧只是打开了音乐 APP 什么都没点，dock 栏媒体信息为什么会被蓝牙音乐覆盖？多音源下媒体 UI 应以什么为准绳？（SIR-6045）**

蓝牙音乐模型对 AVRCP 的播放状态与元数据回调照单全收：手机打开音乐 APP 时，AVRCP 会推送一次播放状态和元数据（哪怕并未播放），模型无条件写入 LiveData，dock 栏订阅后即被改写。缺陷库根因点中要害——更新媒体信息前未判断当前音频焦点。

修复在两条数据入口统一加焦点闸门（节选自修复 diff）：

```kotlin
+    private fun isCanChangeInfo(): Boolean {
+        App.app?.getCarAudioManager()?.getCarFocusForZoneId(CarAudioManager.PRIMARY_AUDIO_ZONE)?.forEach {
+            if (TextUtils.equals("com.arcvideo.car.ncm.music", it.packageName)) return false
+        }
+        return true
+    }
 // onPlaybackStateChanged / onMetadataChanged 写入 LiveData 前统一：
+    if (!isCanChangeInfo()) return@post
```

查询车机音频服务主音频区的焦点持有者列表，车机本地音乐应用持有焦点期间，蓝牙推送的状态与元数据被丢弃。焦点切回蓝牙（或本地音乐不在焦点列表）时恢复正常更新。示例中的主区常量限定了查询范围，包名字符串是该车型本地音乐应用的标识。焦点服务或应用对象为空时函数会默认放行，因此初始化竞态仍可能短暂显示错误音源。

通用规则：多音源共存的车机系统里，媒体信息 UI 的更新必须以"当前音频焦点"为准绳，事件驱动（回调推送）不能替代焦点仲裁——这是 AOSP 车机媒体框架中 MediaSession 与音频焦点的正确协作方式。边界：判定用焦点持有者包名硬编码是权宜之计，音源应用换包名或出现多个本地音源时需同步维护。音频服务未就绪时闸门放行，极端时序下仍可能闪现蓝牙信息。

**Q23: 焦点闸门已经拦住蓝牙音乐的信息更新，dock 栏为什么仍在两种音源间来回切换？（SIR-6290）**

守卫只拦了"数据更新"，没拦"驱动源"。焦点被本地音乐持有时，播放状态回调提前返回，但此前已启动的 500ms 周期进度刷新任务仍在自我延时循环执行，持续写进度 LiveData，并经前台服务触发媒体状态对外刷新——蓝牙侧虽然被挡住"信息变更"，却仍通过进度通道不断向外推送"蓝牙音乐仍在"的信号，dock 把它与本地音乐的真实状态交替渲染。

修复只需在早退分支先停掉驱动源（节选自修复 diff）：

```diff
                 if (state != null && !mIsBCallInCall) {
-                    if (!isCanChangeInfo()) return@post
+                    if (!isCanChangeInfo()) {
+                        stopProgressUpdate()   // removeCallbacks，终结 500ms 自循环
+                        return@post
+                    }
```

蓝牙音乐重新拿到焦点后，播放状态回调会自然重启进度任务，不存在永久停摆。

通用规则：提前 return 的守卫分支要检查"是否还有定时任务或循环在跑"——只拦数据更新、不拦驱动源，等于没拦住。一个对外可观察的状态往往有多个写入方，修"状态乱跳"类问题要梳理全部写入路径，而不是只改判断条件。"媒体信息受焦点管控、进度刷新不受管控"这类双通道不一致，是媒体源切换抖动的常见来源。

**Q24: 音频焦点被抢占后蓝牙音乐已暂停，播放按钮为什么还显示"可播放"？门控分支丢弃事件时应如何取舍？（SIR-7909）**

焦点闸门的早退分支一刀切跳过了全部状态更新——"已暂停"这个关系到操作按钮正确性的状态也被丢弃，播放按钮停留在旧的播放态。缺陷库根因"未更新数据"指的就是门控提前返回时把状态同步一并丢掉。

修复是最小放行：早退分支里对"暂停"状态单独补发状态迁移（节选自修复 diff）：

```diff
                     if (!isCanChangeInfo()) {
                         stopProgressUpdate()
+                        if (state.state == PlaybackState.STATE_PAUSED) {
+                            stateChange(state.state)   // 只放行暂停态，按钮状态机照常切换
+                        }
                         return@post
                     }
```

按钮状态机完成切换，同时保持"歌名、封面等曲信息不刷新"的原语义——只放行暂停态，不为此打开整个信息更新门控。

边界：早退分支的完整播放状态对象仍未更新，依赖完整状态（含进度）的订阅者拿不到这次暂停事件，若进度条也依赖该状态需另行确认。通用规则：门控、早退分支不能一刀切丢弃事件，要按事件类型评估各自的消费方——按钮正确性与曲名可否刷新是两个维度，选择性放行关键状态即可。"界面显示与真实状态脱节、且只在特定门控路径下发生"是状态同步类 bug 的典型信号，排查时优先看回调里的提前 return 都跳过了哪些订阅者更新。

**Q25: 蓝牙音乐暂停后再点播放、上一曲、下一曲，为什么弹出"音频使用中，无法播放"？一个焦点查询函数的空集合分支错在哪？（SIR-7383）**

按键入口的焦点闸门用 forEach 遍历主音频区的焦点持有者列表，命中媒体音量组才返回"可点击"。列表为 null 或空集合时循环体一次都不执行，落到方法尾返回"不可点击"——"查不到任何焦点持有者"被误判成了"音频被别人占用"。而蓝牙音乐暂停后手机侧通常会释放音频焦点，空列表恰是暂停态的常态，于是"暂停后再操作"必现误拦截。正确语义是：空列表等于无人占用，应判定可点击。修复后的判定形态（节选自修复 diff，遍历判定细节略）：

```kotlin
private fun isCanClick(): Boolean {
    val audioFocusInfos =
        App.app?.getCarAudioManager()?.getCarFocusForZoneId(CarAudioManager.PRIMARY_AUDIO_ZONE)
    if (audioFocusInfos.isNullOrEmpty()) return true   // 空列表 = 无人占用 = 可点击
    audioFocusInfos.forEach {
        // 遍历判定是否持有媒体音量组焦点（节选自真实 diff，判定细节略）
    }
    return false
}
```

通用规则：`集合?.forEach { if (条件) return true }` 加方法尾 `return false` 是经典陷阱——"未命中"与"无数据"两种语义被混为一谈。先写空集合分支再遍历，可同时修正语义与可读性。判断规则："暂停后无法恢复播放"类问题优先怀疑音频焦点查询结果的状态相关性——暂停态焦点常被释放，任何依赖"当前焦点必须是媒体组"的闸门都会在暂停态误伤。遍历判定的粒度也决定了第三方应用持有媒体组焦点但未出声时仍会被拦截，这属于策略本身的限制。

**Q26: 手机互联（carlink）断开后，dock 栏为什么会显示蓝牙音乐的异常媒体信息？"无音源"判定应如何维护？（SIR-8019）**

蓝牙媒体会话在断连场景上报的元数据中，歌手字段是协议占位字符串 "Unavailable"，而"无音源"判定只枚举了空值、"MUSIC_SOURCE_BT"、"Not Provided" 三种占位取值——断连取值未覆盖，无音源标志保持 false，蓝牙侧继续向 dock 推送异常媒体更新。

修复是把断连占位值归入无音源判定（节选自修复 diff）：

```diff
         mIsNoMusicSource =
             (TextUtils.equals(title, "MUSIC_SOURCE_BT") || TextUtils.equals(title, "Not Provided") || TextUtils.isEmpty(title))
-                    && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT"))
+                    && (TextUtils.isEmpty(artist) || TextUtils.equals(artist, "MUSIC_SOURCE_BT") || TextUtils.equals(artist, "Unavailable"))
```

改动虽然只放宽一个布尔条件，但该标志是"是否推送更新"的单一决策点，一处判定修正即全局生效，dock 卡片随之正确显示无音源状态并拦截误操作。

通用规则：对外部媒体源（MediaSession 元数据）做"无内容"判定时，必须枚举协议中的全部占位字符串。新增互联协议后要重新核对这些魔法值——占位值是协议的一部分，不是脏数据。场景类显示异常的排查路径：先在数据入口打点确认真实上报值，再补判定分支，而不是在 UI 层打补丁。边界：若正常歌曲的歌手字段恰为该占位串会被误判为无音源，但它是协议保留值，实际冲突概率极低。

**Q27: 收到 A2DP 或 AVRCP 任一 profile 连接成功的广播就立即分配音频源并切换通路，为什么耳机可能无声？多 profile 的时序绑定原则是什么？（SIR-4193）**

连接广播到达时，设备的 A2DP 标志尚未落位、HFP 状态也未记录——音频源分配与通路切换发生在 profile 状态不完整的时点上，耳机侧音频通路没有被正确建立。原代码把"任一 profile 连上"当作"可以切通路"的信号，是典型的时序错误。

修复把 HFP 与媒体 Profile 的处理拆到各自状态分支，删掉"首个广播即抢跑"的通用切换（节选自修复 diff）：

```java
             if (profileId == BtAdapterMessage.PROFILE_ID.HFP) {
+                switchHfp(state, device, address);          // HFP 连接后置语音识别通路，断开清除
                 device.setHfpFlag(stateValue);
             } else if (profileId == BtAdapterMessage.PROFILE_ID.A2DP_SOURCE
                     || profileId == BtAdapterMessage.PROFILE_ID.AVRCP_TARGET) {
+                switchAvrcpAndA2dp(state, device, address); // flag 落位才分配音频源，断开显式释放
                 device.setA2dpSourceFlag(stateValue);
```

要点有二：切换动作应绑定到它所依赖的 Profile 状态，而不是收到任一连接广播就执行统一切换。代码片段中 `switchAvrcpAndA2dp()` 位于 `setA2dpSourceFlag()` 之前，所以不能仅凭该片段声称连接标志先落位。必须继续检查这两个方法是否自行校验/更新状态，不能把未证明的调用顺序当作协议保证。音频源这类资源型句柄必须成对实现分配和释放，断开分支同样处理，否则复用与重连时状态错乱。

边界：底层广播顺序变化时，要重新验证状态分支与音频源分配/释放的对应关系。各分支应打印完整的 profile 状态日志，便于核对事件到达顺序与标志写入时机是否匹配。

**Q28: LIN 滚轮拨动一次切歌却跳过两首，这类"动作翻倍"的机制根源是什么？接入新硬件信号前应先确认什么？（SIR-5836）**

同一输入被两条链路各消费一次：应用侧服务监听滚轮对应的车辆属性信号，直接调用切歌方法。框架层（FWK）又把滚轮事件转成媒体会话命令，回调到媒体服务的跳曲接口——后者同样调用切歌方法。两条链路各切一次，即"跳两首"。

第一次提交先软关闭应用的车辆属性读写通道（节选自修复 diff）：

```diff
                 override fun onLifecycleChanged(isReady: Boolean) {
-                    mIsReady = isReady
+                    mIsReady = false   // 就绪标志恒 false，应用的属性读写通道被关死
                     if (isReady) {
                         registerPropertyCallbacks(callbackPropertyIds, carPropertyEventCallback, true)
```

第一次提交把 `mIsReady` 固定为 `false`，用于抑制应用侧属性通道的消费。示例中的 `if (isReady)` 仍会调用注册回调，因此不能把这行代码解释为取消了底层属性订阅。最终修复由后续提交完成：移除媒体会话回调中的重复切歌实现，只保留一条切歌路径。前后两次提交共同完成，复盘时应合并看待。

通用规则：接入新硬件信号前必须先确认框架层是否已有同义实现——车机多媒体最常见的"动作翻倍"就是框架与应用双通道重复消费同一输入。修复要收敛到一条责任链，而不是两边各改一点。边界：用恒为 false 的就绪标志"软关闭"一个服务通道是隐晦做法，若该通道本就没有调用点甚至毫无效果，不如直接删除注册或注释调用链，让修复意图在 diff 中可见。

**Q29: 蓝牙已连接、联系人同步中，收藏和最近通话页却仍显示"去连接"控件——主线程状态回调里 postValue 引入了什么乱序窗口，重连时还要补什么？（SIR-6509）**

断连分支用 `postValue` 把"未连接"状态异步投递到主线程，与紧随其后的 CONNECTED 事件存在乱序/覆盖窗口：断连态尚未落地时，CONNECTED 分支已经执行完数据加载并同步发布了新状态，迟到的断连态随后落地，把页面覆盖回"未连接"。而且旧的 CONNECTED 分支从不检查当前是否已处于断连态，陈旧残留没有出口。

修复两步：主线程回调里断连改 `setValue` 同步落值，保证状态严格按事件顺序切换。CONNECTED 时发现残留的断连态立即复位（节选自修复 diff）：

```diff
-            uiState.postValue(new CallLogUiState.BluetoothDisconnected());
+            uiState.setValue(new CallLogUiState.BluetoothDisconnected());   // 主线程回调内同步落值
         } else if (connectionState == BluetoothConnectionState.CONNECTED) {
             clearCallLogsIfDeviceChanged();
+            if (uiState.getValue() instanceof CallLogUiState.BluetoothDisconnected) {
+                uiState.setValue(new CallLogUiState.Empty(""));   // 复位陈旧断连态
+            }
```

`setValue` 同步更新让"最后写入"一定对应"最新事件"。复位检查把任何已残留的未连接态强制归位，页面随 PBAP 同步数据刷新为正常列表。收藏与通话记录两个同源 ViewModel 必须成对修改，否则同一问题在另一半复发。

边界：`setValue` 要求主线程调用，此处在 LiveData source 回调内成立，若调用线程变化需重新评估。判断规则：主线程回调里更新连接类状态用 `setValue` 而非 `postValue`——postValue 的延迟合并语义会让"最后写入"不一定是最新事件。状态机要给"陈旧状态"留出口，进入新状态时检查并清理上一态残留。

**Q30: 两路蓝牙通话来回切换时偶现自动切回上一路，为什么应用多发的一条 hold 指令是根源？切换这类异步操作要配哪些状态管理？（SIR-7375）**

目标项目的通话服务在恢复等待中的通话时会同步处理当前活动通话的保持状态。旧实现又显式调用 `holdCall(activeCall)`，随后调用 `unholdCall(holdingCall)`，重复发出保持语义。两组指令与状态回调交错时，协议栈可能把刚恢复的通话再次切回。偶现由时序竞争产生，又因切换未完成时允许再次点击而放大。

修复把切换收敛为只下发一条恢复指令，hold 交给框架自动完成，并为切换建立显式"进行中"状态（节选自修复 diff）：

```diff
             if (activeCall != null && holdingCall.getState() == Call.STATE_HOLDING) {
-                holdCall(activeCall);       // 多余：Telecom unhold 时会自动 hold 对方通话
-                unholdCall(holdingCall);
+                mSwitchFromCall = activeCall;
+                mSwitchToCall = holdingCall;
+                mHandler.postDelayed(mCallSwitchTimeout, CALL_SWITCH_TIMEOUT_MS);
+                // Telecom 会自动保持当前通话，只下发一次恢复操作。
+                unholdCall(holdingCall);
```

配套状态管理按操作生命周期执行：

1. **入口防重入：**`isCallSwitching()` 为真时忽略新的切换请求。
2. **确认完成：**状态回调里以 `from` 变为 HOLDING 且 `to` 变为 ACTIVE 作为该项目的完成条件。
3. **处理超时：**5 秒后若仍未满足完成条件，结束等待并清理切换态。超时本身不代表通话切换成功。
4. **统一异常清理：**通话挂断、蓝牙断开或请求异常时调用 `finishCallSwitch`。

切换期间浮窗与三方页禁用切换、接听按钮，防止连点叠加指令。

边界：切换期间按钮短暂禁用（半透明）是合理交互约束。完成判定依赖目标项目的 Telecom/ConnectionService 状态契约，不能推广成所有通话服务都由 Telecom 自动 hold 当前通话。调用框架 API 前应核对其真实副作用，跨状态机的异步操作（切换、合并、转接）要有进行中状态、完成判定、超时处理和异常清理。

**Q31: 第一路通话接通后拨打第二路，Telecom 暂时报出 active 加 dialing 时，浮窗为什么不能直接切成"呼叫中"？（SIR-8071）**

在目标手机不支持通话保持时，第二路呼出期间可能暂时报出"一路 active + 一路 dialing"。这是该通话切换过程中的中间状态，不应一概称为非法组合。旧逻辑尝试三方通话 UI 失败后落入通用分支，直接显示"呼叫中"并遮住第一路已接通界面。

修复为该组合增加守卫：保持当前通话界面不动，等第一路被 held 后由后续状态回调正常刷新（节选自修复 diff）：

```diff
+        // 第二路刚发起但第一路尚未保持时，继续显示第一路通话界面，避免误显示为"呼叫中"
+        if (calls.size() >= 2
+                && uiCallManager.getCallWithState(Call.STATE_ACTIVE) != null
+                && uiCallManager.getCallWithState(Call.STATE_CONNECTING, Call.STATE_DIALING) != null) {
+            LogUtils.i(TAG, "Keep ongoing UI while waiting for the active call to be held");
+            return;
+        }
```

正常单路呼出（只有一路通话）不进该分支，行为不变。

边界：若个别手机之后仍不把第一路置为 held，第二路的呼叫 UI 会持续等待，因此要分别验证支持与不支持 hold 的设备，并记录等待条件与超时策略。多路通话应明确列出产品支持的状态组合和界面行为。未支持或仍在转换中的状态不能无条件落入通用“呼叫中”分支。

**Q32: 并发通话的联系人兜底显示为什么不能把姓名和号码分开缓存？怎样保证两者属于同一次拨号？（SIR-6815）**

通话查不到联系人时的兜底显示数据来自 `UiCallManager` 的两个独立静态变量：姓名缓存与"最后呼出号码"各自在不同代码路径、不同时机写入。兜底判定却用姓名变量取名、用号码变量配号再比较。两路通话并发更新时，号码可能与"最后呼出号码"匹配，而姓名仍是第一路的缓存——判定基准来自两个时间点的快照，姓名与号码张冠李戴。

修复让兜底判定使用同一份“号码-姓名”数据，但示例里两个静态字段仍是分两步赋值，并非线程意义上的原子写入（节选自修复 diff）：

```diff
-    private static void updateLastDisplayName(String name) {
+    private static void updateLastDisplayInfo(String number, String name) {
+        displayNumber = number;
         displayName = name;
     }
-                    String lastCallNumber = UiCallManager.get().getLastCallPhone();
+                    String lastCallNumber = UiCallManager.getDisplayNumber();
```

两字段由同一个方法更新，减少调用点把姓名和号码来源写错的机会。`displayNumber` 与 `displayName` 仍分别赋值，若读取和更新可能并发，读者仍可能观察到新号码配旧姓名。真正需要线程原子性的实现应以单个不可变 `DisplayInfo(number, name)` 对象替换一个 `volatile` 引用，或用同一把锁保护成对读写。原“最后呼出号码”变量继续专职拨出补全，职责分离。

判断规则：成对使用的数据应作为一个不可变值发布，或在读写两侧使用同一同步机制。仅把两次赋值放进同一个方法不构成原子更新。兜底逻辑的号码比较基准和姓名必须来自同一拨号快照。

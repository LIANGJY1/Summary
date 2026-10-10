# 状态缓存与启动时序：从卡开机到缓存失步的因果链

> 学习资料（文章模式沉淀）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：该项目 761 条缺陷修复中状态管理与缓存占 77 条，叠加异步时序后的高危形态集中在启动时序（类加载路径执行 IO 导致卡开机）、状态机提前 return 盲区、暂存数据覆盖写、退出路径未复位、缓存与真实源失步五类，本文沿因果链提炼各形态的可迁移规则与排查入口。主线程 Binder 分流、迟到回调失效、广播事务归属等异步范式由 [09-defect-main-thread-async.md](../02-app-framework/09-defect-main-thread-async.md) 承载，本文不重复展开。偶现问题的排查方法与崩溃形态见 [14-app-crash-patterns.md](../12-performance/14-app-crash-patterns.md)，组合状态跨端同步见 [08-kanzi-state-sync.md](./08-kanzi-state-sync.md)，开机存储准备的平台背景见 [../01-architecture/02-system-boot.md](../01-architecture/02-system-boot.md)。2026-09-26 修订：补代码级讲解与深案例覆盖。Q 序列即结构，供 atlas 同源直读。2026-09-26 二次修订：消除跨题引用，改为题内自足；段落并列项拆为列表；新增 02-system-boot Q7（同入口连点的跳转锁 + launchMode 双保险）；新增 Q4（privapp-permissions 白名单未同步导致开机异常）、Q22（派生状态刷新时机链）、Q23（列表渲染空值兜底）。2026-10-04：将 pending 回显条件、Lifecycle-bound LiveData 重放与状态消费边界拆为 Q27，承接提交治理册迁出的 Android 技术细节。

**Q1: [learning] 单例 object 的 init 块在应用启动极早期执行 mkdirs 落盘，为什么会卡住整个开机？（SIR-8227）**

因为类加载路径上的 IO 落在了系统存储未就绪的窗口期。完整因果链：Kotlin object 单例的 init 块在类首次被触碰时执行。该工具类单例在应用进程极早期（开机阶段）就会被引用。线刷升级后首次上电属于“首次开机”场景，系统 vold 尚未完成用户 CE（Credential Encrypted）存储准备。init 块里对 CE 路径执行 `file.parentFile?.mkdirs()` 并从磁盘加载，提前触碰未准备的存储触发 `prepareUserStorage` 失败（ENOTEMPTY），阻塞系统启动流程——表现为持续卡在开机 logo，A 级阻塞问题。

这条链上没有任何“业务逻辑错误”：目录创建、磁盘加载单独看都正确，错的是执行时机被类加载机制钉死在最早窗口。修复的第一刀就是把 `mkdirs` 从 init 块里删掉，并新增统一的就绪门控查询（节选自真实 diff）：

```diff
--- component/CommonTools/src/main/java/.../manager/SeatUserManager.kt
     init {
-        file.parentFile?.mkdirs()
         cache = loadFromDisk()
     }
+    private fun isUserStorageReady(): Boolean {
+        return try {
+            val ctx = ContextGet.applicationContext() ?: return false
+            val um = ctx.getSystemService(Context.USER_SERVICE) as UserManager
+            um.isUserUnlocked(Process.myUserHandle())
+        } catch (e: Exception) {
+            LogUtils.e(TAG, "isUserStorageReady fail", e)
+            false
+        }
+    }
```

判断规则：启动时序敏感代码禁止在类加载路径执行 IO，一切落盘动作都要推迟到存储就绪门控之后——写路径先查 `UserManager.isUserUnlocked`，未就绪则跳过本次写入。修复后的工程结构也说明时机安排：初始化不再创建目录，`mkdirs` 挪进写路径懒执行。

**Q2: [learning] 为未就绪的用户存储加门控时，读路径与写路径为什么要区别对待？各分支如何设计？**

因为读与写对存储的依赖强度不同：读已存在文件失败只会得到空数据（可接受），写/建目录会直接触碰未准备的存储并阻塞系统。写路径门控与 `mkdirs` 懒执行的落点（节选自真实 diff）：

```diff
--- SeatUserManager.kt（saveToDisk）
+        val isReady = isUserStorageReady()
+        if (!isReady) {
+            LogUtils.d(TAG, "saveToDisk skip: user CE storage not ready")
+            return
+        }
         var fos: FileOutputStream? = null
         try {
+            file.parentFile?.mkdirs()
             fos = atomicFile.startWrite()
```

SIR-8227 的门控设计按读写区分各分支：

- **初始化**：只保留内存加载（loadFromDisk 只读），删去 mkdirs——读路径无需门控。
- **写盘**：先查 `UserManager.isUserUnlocked`，未就绪直接跳过本次写入（内存态仍在），`mkdirs` 挪进写路径懒执行。
- **addUser**：未就绪返回 -1，让调用方明确感知“没成功”，防止假成功。
- **userExists**：未就绪返回 true——防止解锁前调用方循环补建用户。

这套设计的门控粒度按读写区分，避免把只读操作也拦下来拖慢启动。代价要明示：未就绪期间的写入被丢弃，若恰有重要配置在解锁前产生，需依赖后续某次写盘补写。userExists 恒真会让解锁前的查询拿到偏乐观结果。判断规则：门控查询统一封装（本例是一个 `isUserStorageReady()`），所有触碰存储的分支共用，返回值语义（跳过/失败/乐观真）按调用方需要分别约定。

**Q3: [learning] “触碰即执行”的静态初始化代码，应按什么标准审查？**

按“开机关键路径”标准审查 object init 块与 static block：初始化体里有没有文件系统写操作（mkdirs、startWrite、SharedPreferences 提交）、有没有跨进程调用、这个类会不会在进程极早期被触碰。SIR-8227 的教训是 A 级卡开机问题的排查起点常在一个不起眼的工具类静态初始化——`object` 的 init 块天然“触碰即执行”，写在里面的任何一行都会在最早引用时刻运行，开发者写时往往只想着“初始化一次”而忘了时机由引用方决定。

审查后的处置分三档：纯内存初始化（建 map、读内存缓存）保留。只读磁盘的初始化可保留但要知道失败后果。任何写操作移出初始化路径，改为首次使用时懒执行加就绪门控。判断规则：工具类单例的 init 块里出现 `mkdirs`/`startWrite` 这类字样，无论功能多正确都要当作启动时序风险处理。

**Q4: [learning] AAOS 上 SystemUI 新增了 android.car.permission.* 特权权限却开机启动异常，而相关代码一行没改，问题出在哪？**

特权权限除在 Manifest 声明外，还必须登记在 `vendor/etc/permissions/` 下的 privapp-permissions 白名单 XML，PMS 安装时才会把 `privileged|signature` 级权限授予该应用。漏登记时权限校验在开机阶段被拒，表现为没有任何对应代码变更的启动异常。

某车机项目 VIR-190 案例（提交 `8ec85414`）：SystemUI 近期接入数字钥匙、丢失模式、车控信号监听等能力，引入大量 `android.car.permission.*` 与 `android.permission.*` 特权权限，但 `whitelist/com.android.systemui.xml` 未同步登记，开机启动时权限校验失败、能力不可用，抛出启动异常。修复是纯配置补登 71 条权限（节选自真实 diff）：

```diff
--- whitelist/com.android.systemui.xml
         <permission name="android.hardware.bluetooth" />
         <permission name="android.hardware.bluetooth_a2dp" />
         <permission name="android.hardware.bluetooth_headset" />
+        <permission name="android.car.permission.CAR_INFO"/>
+        <permission name="android.car.permission.CAR_POWER"/>
+        <permission name="android.car.permission.MONITOR_INPUT"/>
+        <permission name="android.car.permission.READ_PRIVILEGED_PHONE_STATE"/>
+        <permission name="android.permission.ACCESS_KEYGUARD_SECURE_STORAGE"/>
+        <permission name="android.permission.CONTROL_KEYGUARD"/>
+        <permission name="android.permission.DEVICE_POWER"/>
+        <permission name="android.permission.DISABLE_KEYGUARD"/>
+        <permission name="android.permission.INTERACT_ACROSS_USERS_FULL"/>
+        <permission name="android.permission.INTERNAL_SYSTEM_WINDOW"/>
+        <permission name="android.permission.MANAGE_ACTIVITY_STACKS"/>
        ...（共 71 条 car/* 特权权限）
```

这类异常的定位价值在于“代码 diff 为空”本身就是线索：白名单在应用仓库的 `whitelist/` 目录下、与 Manifest 分离，新增权限时极易只改 Manifest。边界：白名单是安装期校验，设备上权限已授予后再改白名单不会生效，必须随版本重新安装。调试期用 `adb shell pm list permissions` 只能看到已授予结果，看不出白名单缺项，要用 `dumpsys package` 的 granted=false 记录确认。判断规则：车机上“某功能在开发期正常、开机后异常”或“权限调用点完全没被执行”，先核对特权权限是否同时登记在 Manifest 与 privapp-permissions 白名单两处。把白名单检查纳入新增权限的提测清单，而不是等开机异常再倒查。

**Q5: [learning] 状态机函数里的提前 return 为什么是清理逻辑的盲区？（SIR-6185）**

因为后续修复的收口动作往往只加在主干路径上，提前 return 的分支被遗忘，于是挂着资源悬空。SIR-6185（A 级，必现）中设备连接状态处理函数对 CARPLAY 非连接状态直接 `return`：此前修复把删除设备的动作收进了断开处理函数，却漏掉这个提前 return 的分支——挂着删除监听器既不触发也不清理，状态回调链悬空。同时承载连接流程的 OSD 浮窗按普通蓝牙设备逻辑处理，不会关闭宿主 Activity，浮窗滞留屏幕表现为“卡死”。修复是把浮窗关闭显式绑定到“CarPlay 连接成功”事件，并在 return 之前补上触发并清空监听器（节选自真实 diff）：

```diff
--- BluetoothFragment.kt（连接结果回调：成功事件直达浮窗关闭）
+        if (deviceType == CARPLAY && isConnected && activity is ConnectChildDialogActivity) {
+            activity?.finish()
+            return
+        }
--- DeviceConnectManager.kt（CARPLAY 非连接分支：return 前补齐清理）
             } else {
+                if (deviceType == CARPLAY && status == CarPlayConstants.SessionStatus.SESSION_STATUS_DEACTIVATED) {
+                    mDeleteDeviceListener?.onDeleteDevice()
+                    mDeleteDeviceListener = null
+                }
                 return
             }
```

判断规则：修改同一条状态链时必须点查该函数的所有出口（每个 return、每个 throw），收口逻辑要么提到出口汇聚处，要么每个出口都补齐。review 时看到函数中段有裸 `return`，就要问“这个分支欠不欠清理”。流程类 A 级卡死的另一个教训：承载流程的临时 UI（浮窗/对话框）必须绑定到成功事件的关闭路径——把关闭显式绑定到流程的成功或失败事件，而不是指望别的流程顺带关掉它，每个流程都要有明确的 exit。

**Q6: [learning] 承载流程的浮窗和页面级弹窗，如何保证“一定有关闭的触发点”？**

关键是给每个浮层指定一个必然到达的关闭触发点，而不是指望其他流程“顺带”关掉它。两类浮层对应两种触发点设计：

- **流程浮窗**：关闭显式绑定到流程的成功/失败事件。SIR-6185 中 CarPlay 连接成功且宿主是对话框 Activity 时直接 `finish()`——不再依赖普通蓝牙流程的列表刷新路径顺带关闭，OSD 浮窗不再滞留。
- **页面级弹窗**：挂在 `onPause` 收起。SIR-1429 中 WLAN/蓝牙/热点弹窗只在用户主动操作时关闭，切 home 后弹窗常驻。修复在 Fragment 的 `onPause` 里检测全局 home 标识后对弹窗逐一安全关闭——`onPause` 是“页面将失去前台”的必经点，而 `onDestroy` 在 Activity 常驻回收场景可能永远不来。

配套细节：关闭动作用封装好的安全 dismiss（内部判活 Fragment 状态），避免在 `onPause` 阶段 dismiss 崩溃。跨模块协作的方案（本例是 SystemUI 埋 home 标识、Setting 消费）必须同批合入，缺一半即无效。Setting 侧的消费实现（节选自真实 diff）：

```diff
--- ConnectFragment.kt
+    override fun onPause() {
+        super.onPause()
+        if (getGSetting("is_click_home", 0) == 1) {
+            mWlanDialogFragment?.safeDismiss()
+            mBluetoothDialogFragment?.safeDismiss()
+            mHotspotDialogFragment?.safeDismiss()
+        }
+    }
```

判断规则：给一个弹窗写 show 的时候同步回答“它由哪些事件关闭、最晚谁兜底”，答不出就是潜在残留。弹窗残留还有另一形态：同一功能存在“页面内弹窗”与“独立 DialogActivity”两个入口宿主时，各宿主的防重逻辑只查自身内部状态、彼此不感知，于是两个同名弹窗叠加。防重状态必须建在跨宿主共享的层面（如进程级静态标志），并挂齐“弹出置位、dismiss 复位、宿主销毁复位兜底”三个钩子。

**Q7: [learning] 同一功能的弹窗存在两个入口宿主时，为什么会各弹一份？防重状态应建在哪里？**

因为各宿主的防重逻辑只查自身内部状态，彼此不感知。SIR-6812 中设置页内用 childFragmentManager 弹 WiFi 弹窗，控制中心入口用独立 DialogActivity 的 FragmentManager 弹同名 Fragment。后者弹出前只检查自己内部的实例可见性，看不到前者已弹的实例，于是两个 WiFi 弹窗叠加。

防重状态必须建在跨宿主共享的层面：本例用 Companion 静态布尔字段做进程级弹窗标志，并给它挂三个钩子——弹出时置位、dismiss 回调复位、宿主 `onDestroyView` 复位兜底。另一个入口弹出前先查标志，已在显示则直接结束自己不再弹（节选自真实 diff）：

```diff
--- ConnectFragment.kt：跨宿主共享标志
+    companion object {
+        @JvmStatic
+        var isWifiDialogShowing = false
+    }
--- ConnectChildDialogActivity.kt：弹出前先查跨宿主标志
     private fun showWifiDialog() {
+        if (ConnectFragment.isWifiDialogShowing) {
+            finish()
+            return
+        }
```

判断规则：同一功能存在“页面内弹窗 + 独立 DialogActivity”多个入口时，“是否已有同语义弹窗”是弹出前置校验，不能各自为政。静态标志是进程内弱约定，三个钩子缺一个都会状态漂移，崩溃后标志未复位可能出现“点不动”假死，onDestroyView 复位是必要的兜底。

**Q8: [learning] 同一入口快速连点导致目标页被重复创建压栈（多次点击弹出多个界面）时，为什么“跳转锁 + launchMode”要成对使用，跳转锁又为什么不能按定时器解锁？**

因为时间窗防抖会吞掉合法操作，而跳转锁按“是否已发起跳转”这一状态判定、返回后立即可再点。单靠防抖或单靠 `launchMode` 都留有缺口，必须成对使用，且解锁时机要绑定“回到发起方”的生命周期。

某车机项目 SIR-7006 案例（B 级，必现）：能量中心主页的“里程管理”入口直接 `startActivity(new Intent(this, MileageManagementActivity.class))`，既无防重入保护，目标页在 Manifest 里也没配 `launchMode`（默认 `standard`）。快速连点时第一次点击尚未让 `MainActivity` 进入 `onPause`，后续点击继续触发 `startActivity`，界面对象逐个压栈。修复是两道保险（节选自真实 diff）：

```diff
--- application/EnergyManagement/src/main/AndroidManifest.xml
         <activity
             android:name="com.android.yadea.energymanagement.view.ui.MileageManagementActivity"
+            android:launchMode="singleTop"
             android:theme="@style/MainTheme" />
--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ onClick(View v)
         if (v.getId() == R.id.ll_module_mileage || v.getId() == R.id.iv_mileage_management_icon) {
+            if (mMileagePageLaunching) {
+                LogUtils.d(TAG, "[MileageDebug] duplicate mileage entry click ignored");
+                return;
+            }
+            mMileagePageLaunching = true;
             Intent intent = new Intent(this, MileageManagementActivity.class);
             startActivity(intent);
@@ onResume()
+        mMileagePageLaunching = false;
```

两道的分工不可互相替代：跳转锁在点击后立即置位、连续点击直接忽略，解决“连点”这一主因。但动画窗口外的极端时序仍可能重复入栈，所以还要 `singleTop` 让目标页复用栈顶实例。反过来只靠 `singleTop` 也不够——后续页面若改成带参数刷新语义，复用实例会掩盖问题。解锁绑在 `MainActivity.onResume()`（从目标页返回发起方）而不是定时器，避免固定延时在低端机上不可靠，也避免“点一次后永远点不动”。

同一缺陷族在项目里反复出现：同一入口多次点击弹出多个里程设置界面、重复 toast 一直弹、重复开关热点后已连接设备列表出现重复与空名条目。边界：`singleTop` 只合并栈顶实例，任务栈里若已存在多个同名实例仍要清栈。跳转锁的状态字段要跟着发起方生命周期走，跨页面共享时改为在目标页 `onDestroy` 统一清零更稳。判断规则：任何 `startActivity` 入口都要问“连点会怎样”——答案必须是"要么被锁挡住，要么被 `launchMode` 合并“，不能是”多压一个页面"。跳转锁与防抖的分工是前者按状态判定、后者按时间窗判定，防抖用在这里会吞掉用户返回后的正常再点。

**Q9: [learning] 以 callback 对象为 key 的暂存 map 用覆盖写，为什么会永久丢失先注册的属性组？（SIR-7936/7938）**

因为覆盖写默认假定同一个 key 只会写入一次，而分批注册打破了这一假定。SIR-7936/7938（A 级，必现，仪表 D/R 档白屏）的因果链：车服务未连接时，属性回调注册被暂存进以 callback 对象为 key 的 map，写法是 `pendingCallbacks[callback] = propertyIds to immediateCallback`——同一 callback 先注册档位/车速等核心属性、再分批注册滚轮属性时，第二次写入整体覆盖第一次，先注册的属性组永久丢失。服务重连后按暂存 map 补注册，档位/车速根本不在列表里，仪表拿不到信号无法进入行车页，白屏。

修复是覆盖改合并：取出已存条目，属性 id 列表拼接去重，立即回传标志取或，再整体写回（节选自真实 diff）：

```diff
--- component/Carlib/src/main/java/.../manager/PropertyManager.kt
         } else {
-            // 如果服务未连接，按 callback 维度暂存
-            pendingCallbacks[callback] = propertyIds to immediateCallback
+            // 同一 callback 可能分批注册多组属性，必须合并而不是整体覆盖
+            val existing = pendingCallbacks[callback]
+            val mergedIds = ((existing?.first ?: emptyList()) + propertyIds).distinct()
+            val mergedImmediate = (existing?.second ?: false) || immediateCallback
+            pendingCallbacks[callback] = mergedIds to mergedImmediate
         }
```

判断规则：写任何 `map[key] = value` 暂存结构时问一句“同 key 会不会合法地再来一次”，会就必须合并写。这类暂存数据结构是跨模块生命线（本例两个 A 级同源），值得用单元测试守护覆盖与合并语义。附带副作用要知晓：标志取或后原本不需要立即回传的批次也会立即回传，属行为放宽。

**Q10: [learning] 命令端与渲染端生命周期解耦时，渲染启动命令被静默丢弃如何根治？（SIR-7922）**

单靠时序对齐无法根治，必须加“重连补偿 + 周期看门狗”双保险。SIR-7922（偶现，桌面白屏）的因果链：渲染启动命令只在“Kanzi 已连接且管理器就绪”时才被真正下发，连接断开窗口期到达的启动命令被静默丢弃且无人补发。应用回到前台时若渲染端尚未重连，重连后也没有机制重跑渲染仲裁——桌面永久白屏。修复双保险：连接建立回调里 post 一次渲染状态仲裁（补发被丢的启动命令）。连接入口再注册一个 2 秒周期的看门狗，发现“前台 + 可渲染形态 + 渲染未启动”持续一个周期就自动补发（节选自真实 diff）：

```diff
--- KanziDataSourceManager.java
+        // Kanzi 连接/重连后重跑渲染仲裁：补发被静默丢弃的启动命令
+        ThreadUtils.getMainHandler().post(this::checkAndPerformRenderState);
+    private final Runnable mRenderWatchdog = new Runnable() {
+        @Override
+        public void run() {
+            if (mIsForeground && (mCurrentMeterForm == 0 || mCurrentMeterForm == 3) && !mRenderStarted) {
+                setRenderStart();
+            }
+            ThreadUtils.getMainHandler().postDelayed(this, RENDER_WATCHDOG_INTERVAL_MS);
+        }
+    };
```

配套是渲染启动/停止维护一个明确的 `mRenderStarted` 标志，看门狗依据它判断，外部若直接改渲染状态必须同步该标志。判断规则：凡是“命令可能被静默丢弃”的解耦链路（命令端与执行端生命周期不同步），都要回答“丢弃后谁来补”——重连补偿解决一次性补发，周期看门狗覆盖所有未预见窗口。两者叠加后竞态从“永久故障”降级为“最多延迟一个周期自愈”。

**Q11: [learning] 手势/动画的退出路径状态复位，必须覆盖哪些东西？漏了会怎样？（SIR-6044）**

必须同时覆盖视觉属性与内部状态变量，漏任何一个都会留中间态。SIR-6044（A 级，偶现，页面卡在驻车界面）中下拉关闭手势在拖动过程中持续修改视图的 Y 平移与透明度，未达关闭阈值取消时回调是空实现——视图永远停在最后一次拖动的中间值，页面“卡”在半透明残像上。内部状态变量也未归零，下次进入页面直接呈现残像，下次手势的基准也从错位处开始。修复是取消回调里把 `translationY`、`alpha` 与内部状态变量一起复位为零值（节选自真实 diff）：

```diff
--- component/CommonTools/src/main/java/.../widgets/LapseTouchLayout.kt
     override fun onExitCancel() {
+        this.translationY = 0f
+        this.alpha = 1f
+        currentTranslationY = 0f
     }
```

判断规则：手势/动画控件必须成对处理进入与取消——拖动回调改了哪些属性，取消回调就要复位哪些。只复位视觉属性不复位状态变量，下次手势基准错位，只复位状态不复位视觉，残像仍在。偶现加 A 级的组合优先怀疑“未达阈值的取消”这类非主流分支，该案例的偶现排查视角（为什么只有取消路径触发）见 [14-app-crash-patterns.md](../12-performance/14-app-crash-patterns.md)。

**Q12: [learning] 设备“断开/移除”只做断连不解绑数据，为什么列表会残留图标或分类错误？（SIR-7499、SIR-6786）**

因为列表渲染依据的是缓存对象上的分类字段与删除记录，两者都不会因断连自动更新。SIR-7499 中设备列表按缓存对象上的互联类型字段区分展示形态。移除流程只执行断开与解除配对，类型字段没有复位，设备从已配对列表转入可配对列表时仍带着互联标记，于是可配对列表里渲染出 HiCar 图标。SIR-6786 则暴露时序问题：删除动作在断开会话后立即执行，此时会话尚未完全断开，删除被底层拒绝——修复是登记删除回调，等到底层断连完成事件到达后再执行删除。

两条规则合起来：一是“先断链路、后删记录”是有状态连接类设备管理的固定时序，删除必须订阅断连完成事件而非同步紧跟调用。二是移除/降级操作必须同步修改缓存对象的分类字段再触发刷新，只断连不改数据是残留类缺陷的典型成因。SIR-7499 修复的关键点是在设备 INVALID 事件里统一修正缓存分类字段（节选自真实 diff）：

```diff
--- DeviceConnectManager.kt：匹配到设备即把互联类型字段归零
     fun isSameDevice(device: CachedBluetoothDevice, btAddr: String) : Boolean{
-        if (TextUtils.equals(device.address, btAddr)) {
+        if (TextUtils.equals(device.address, btAddr) || TextUtils.equals(device.deviceId, btAddr)) {
             device.phoneCarConnectionType = 0
             return true
         }
```

多协议（CarPlay/HiCar/CarLink）各自实现一套状态回调时，“改数据、发刷新、清回调”的公共收尾应收敛到一个函数统一调用（本例的 `changeSourceDataType`），消除各协议时序错开造成的遗漏。

**Q13: [learning] “凭空弹出上个会话的确认弹窗”，残留状态的排查起点是什么？（SIR-5749）**

起点是顺着提示文案里出现的实体，反查它“何时被记录、何时应被清除”。SIR-5749 中未连接任何互联协议时连接 CarPlay，却弹出“是否断开已连接的 HiCar 并切换”的确认弹窗——判断依据是一个静态的“当前互联设备”变量，断开处理只重置了连接类型没有清空该变量，上个 HiCar 会话的设备对象残留，连接 CarPlay 时被误判为设备切换。低概率（10%~40%）恰是线索：必须先有“连过又断开”的前置序列才复现。

修复是断开路径同步清空变量，使“未连接”状态下它必然为 null。同函数里同设备分支执行完回调后补显式 `return`，截断隐式落入后续弹窗逻辑的可能（节选自真实 diff）：

```diff
--- DeviceConnectManager.kt
     private fun handleDeviceDisconnect(deviceType: String) {
         mCurrentConnectType = 0
+        SCurrentThirdDevice = null
         when (deviceType) {
```

判断规则：全局/静态的“当前 X”变量是弹窗误判类缺陷的高发源头，赋值点之外必须逐一盘点清空点（断开、注销、超时路径都要置空）。一个状态变量的赋值与清理必须成对设计，同一变量反复出问题时应收敛到单一 Owner 并以事件对外分发。

**Q14: [learning] “请求-回执”式开关在异常路径上锁死或半同步，如何补齐状态机？（SIR-2032、SIR-5722）**

两个案例对应状态机的两类缺口，修法一致——穷举回执态并让每个分支显式收敛状态。SIR-2032 补上的正是缺失的失败分支（节选自真实 diff）：

```diff
--- HotspotDialogFragment.kt（AP 状态回执）
         when (state) {
             WIFI_AP_STATE_DISABLED -> mBindingHeader.switchHotspot.disableOverlay()
             WIFI_AP_STATE_ENABLED -> mBindingHeader.switchHotspot.disableOverlay()
+            WIFI_AP_STATE_FAILED -> {
+                mBindingHeader.switchHotspot.isChecked = false
+                mBindingHeader.switchHotspot.disableOverlay()
+                ToastUtils.showMsgToast(requireContext(), getString(R.string.hot_point_open_failed))
+            }
         }
```

- **漏掉异常回执**：热点开关点击后上交互锁等状态回执，回执处理漏掉 `FAILED` 态，开启失败时锁永不释放，按钮锁死（SIR-2032）。修复补上 FAILED 分支，回滚开关选中态、解锁并提示失败。
- **回执只管局部不管核心**：回执处理只解除交互遮罩、从不回写开关选中态，流程中途经过任何状态回调，UI 与真实状态脱钩成“开启未连接”的悬挂态（SIR-5722）。修复让每个回执分支都显式回写选中值。

判断规则有三条：

- **穷举回执态**：“请求 → 锁交互 → 等回执 → 解锁”的状态机必须穷举所有回执态，`when` 全集配合 `else` 兜底。
- **交互锁要有超时保险**：依赖回调释放的锁，回调丢失即永久卡死。
- **状态回调是唯一真值来源**：开关类 UI 里点击时的乐观置位只是过渡效果，任何分支都不能漏回写。

排查顺序上先补全量状态日志复现时序再修逻辑，本案例先加日志再补分支的做法值得沿用。

**Q15: [learning] “覆盖层 + 禁用控件”的防抖设计为什么会死锁成“显示关闭、点击无响应”？（SIR-8679）**

因为某条路径之后没有人负责解锁。SIR-8679 中开关的防抖机制是：点击后显示覆盖层并禁用控件，等状态回执后解除。“关闭蓝牙二次确认”成功的路径把开关置为关闭、开启覆盖层置灰——但这条路径之后无人调用解除覆盖，开关保持禁用、透明度停在 0.5。此时点击落入覆盖层监听，又被“开关已关闭直接 return”的守卫吞掉，形成死锁。修复把开关显式建模为三态：透明度 0.5 为过渡中（点击一律忽略）、1.0 为已开启待操作（覆盖层点击弹确认）、解除覆盖为已关闭可点击。开启成功的回执显式恢复 1.0（节选自真实 diff）：

```diff
--- BluetoothFragment.kt：alpha 三态守卫与恢复
         mBindingHeader.sw.setOnOverlayClickListener {
-            if (!mBindingHeader.sw.isChecked) return@setOnOverlayClickListener
+            if (!mBindingHeader.sw.isChecked || (mBindingHeader.sw.isChecked && mBindingHeader.sw.switchCompat.alpha != 1.0f)) return@setOnOverlayClickListener
             ManagerConstants.STATE_ON -> {
-                mBindingHeader.sw.enableOverlay()
+                mBindingHeader.sw.enableOverlay(1.0f)
```

判断规则：覆盖层加禁用控件的防抖设计，必须保证每条路径最终都回到可交互态，评审时对每条路径问“这条路径谁来解锁”。把“遮罩开关”与“置灰”两个正交概念揉进一个布尔参数的 API 是状态错位的温床，显式参数化后各调用点语义自明。复现口诀是“反复/打断操作”：每次 toggle 留下的残值（透明度、enabled、覆盖层可见性）逐项对照，残值累积处就是死锁点。

**Q16: [learning] “乐观更新 + 超时回弹”的开关，回弹发生时为什么联动区域会脱节？（SIR-8669）**

因为回弹被当成了纯视觉行为，没有当作一次状态变更事件通知业务层。SIR-8669 中开关点击后视觉先翻到目标态并下发命令，1 秒内信号未确认则回弹到之前状态——但调用方只注册了变更回调，没有接回弹回调，回弹把开关翻回“关”的同时，控制下方内容区显隐的刷新没有执行，出现“开关关着、内容区还在”的脱节。修复是给公共 setup 函数加可选的回弹回调参数，把回弹后的联动刷新接上（节选自真实 diff）：

```diff
--- AssistedDrivingFragment.kt
-    private fun setupAdasSwitch(switch: CustomSwitchCompat, tag: String, action: () -> Unit) {
-        switch.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
+    private fun setupAdasSwitch(switch: CustomSwitchCompat, tag: String, action: () -> Unit,
+                                onRebound: (() -> Unit)? = null) {
+        switch.setClickFastWithRebound(
+            viewLifecycleOwner.lifecycleScope,
+            onReboundCallback = { _ ->
+                // 1秒超时回弹：开关视觉已回滚，下方设置项显隐需同步
+                onRebound?.invoke()
+            })
```

调用点传入 `updateRearCollisionWarningUI(STATE_OFF)` 后，正向点击回弹时内容区同步隐藏。二次确认关闭流程的回弹路径同样补上回调恢复内容区，两条回弹路径的联动都不再缺席。

判断规则：凡是“乐观更新 + 超时回弹”的控件，回弹就是一次完整的状态变更，回调里要恢复的不止控件本身，还有所有因它联动的区域。公共控件封装给回调默认空实现虽然降低接入成本，但调用方漏接时错误被静默吞掉——新开关联动内容区时，把“回弹联动”列为接入清单项。复现用例两条分支都要覆盖：点击后不等信号立即再点，以及点击后静置等超时回弹。

**Q17: [learning] 首次操作就误触发安全拦截，同步读取车辆信号的缓存语义坑在哪？（SIR-5788）**

同步 get 对“缓存未就绪”没有独立的表达：首次进入页面时属性服务尚无该信号的缓存值，同步读取返回默认值或错误码，业务侧的哨兵比较（`value != 0`）把它与“真实非 P 档”混为一谈，安全拦截误弹“非 P 档不可操作”并中断流程——首次访问必然缺缓存，所以必现。修复是把读取与后续动作整体移入 IO 协程异步执行，绕真实取值。读到的确实非 P 档时仍正确拦截，安全语义保留（节选自真实 diff）：

```diff
--- SystemFragment.kt（恢复确认回调）
             override fun confirm(content: Any?) {
-                val value: Any =
-                    settingVehicleService.getAnyProperty(CarPropertyIds.PCU_ACTUALGEARFEED)
-                log("reset gear: $value")
-                if (value != 0) {
+                lifecycleScope.launch(Dispatchers.IO) {
+                    val value: Any =
+                        settingVehicleService.getAnyProperty(CarPropertyIds.PCU_ACTUALGEARFEED)
+                    log("reset gear: $value")
+                    if (value != 0) {
```

判断规则两条：车辆信号的同步读取带“缓存未就绪”语义，安全类判断要么订阅信号最新值，要么显式异步拉取，不能裸信同步返回值。`value != 0` 这类哨兵比较要先区分“取不到值”与“真实业务值不满足”两种情况，错误码与业务值混判是误拦根源。附带收益：低频高危操作的信号 IO 移出主线程顺带消除 ANR 风险，但异步化后注意生命周期兜底（toast 用 application context 更稳）。

**Q18: [learning] 上一次连接失败的广播为什么会污染新弹窗？防线应设在哪里？（SIR-6386）**

因为系统服务的广播异步到达且可能粘性重放，而新弹窗不区分“这是不是自己发起的连接”。SIR-6386 中 WiFi 密码输错后立刻点另一个 WiFi，新弹窗在 onStart 重新注册广播后收到了上一次连接失败的迟到广播，直接把弹窗置为错误态——明明还没输入新密码。修复是加“请求上下文”门闸：引入 `isConnecting` 标志，用户点击连接后才置 true，处理认证失败的入口先查门闸，消费失败结果时复位。上一次的迟到广播到达时门闸是关的，直接 return（节选自真实 diff）：

```diff
--- WlanCustomEditDialogFragment.kt
     private fun handleAuthenticationError(newState: SupplicantState?, detailedState: NetworkInfo.DetailedState) {
+        if (!isConnecting) {
+            return
+        }
         if (detailedState == NetworkInfo.DetailedState.DISCONNECTED) {
             showPwdError()
```

门闸的置位在“连接”按钮点击处（`isConnecting = true`），复位在 `showPwdError()` 消费失败结果处，生命周期是完整的一对。

判断规则：WiFi/蓝牙这类系统服务的广播处理必须带请求上下文（是否正在连接、连的是哪个目标），否则过时事件必然污染新会话。更严格的版本是给每次连接带 requestId 或目标标识做比对。防线要设在处理函数而不是注册处——`onStart` 注册的瞬间就可能收到历史粘性广播，注册处无法拦截。

**Q19: [learning] 界面销毁时替单例 Manager 注销广播，为什么会导致设备在两个列表同时显示？（SIR-6206）**

因为注销范围与对象生命周期不对齐。SIR-6206 中蓝牙子界面在 `onDestroy` 调用单例管理类的广播注销方法，而该管理类是跨界面共享的单例，它的广播监听服务所有界面——界面关闭后广播链路被掐断，设备从“可配对”转入“已配对”的状态迁移事件无人处理，中间态数据残留，下次进入时同一设备在两个列表同时出现。修复是界面只注销自己的回调（`unregisterCallback(this)`），不注销管理类自身的公共广播通道（节选自真实 diff）：

```diff
--- application/Setting/src/main/java/.../diologfragment/BluetoothAnwFragment.kt
     override fun onDestroy() {
         BtAnwManager.getInstance().setIsInitStatus(false)
         BtAnwManager.getInstance().unregisterCallback(this)
-        BtAnwManager.getInstance().unregisterReceiver()
+//        BtAnwManager.getInstance().unregisterReceiver()
```

判断规则：短生命周期的界面不要替长生命周期的单例注销其自身的监听，注销范围必须与对象生命周期对齐。“只注销自己的回调、不注销公共通道”是单例加监听者模式的标准做法。列表数据双显类问题，优先排查状态迁移事件链是否在某个生命周期节点被掐断。附带工程卫生项：该修复以注释方式保留注销调用，注释掉的代码应尽快删除并说明原因，否则容易在后续清理中被“恢复”导致回归。

**Q20: [learning] 展示“当前值”却显示旧值，闭包捕获与循环外变量这两种形态如何识别与修复？（SIR-7167、SIR-6611）**

共同根源是“值的定格时刻”与“使用的时刻”不一致，识别特征是显示的值像“上一个”而不是“当前”：

- **闭包捕获**：初始化时把设备名取进局部变量，点击监听的闭包捕获的是当时的值而非引用，此后数据变化弹窗每次都拿到陈旧快照（SIR-7167，编辑弹窗预填旧名称）。修复是点击时刻现场重新调用 getter。
- **循环外变量**：临时变量声明在循环外且只在部分分支赋值，某轮迭代没有命中赋值分支时沿用上一轮残留值（SIR-6611，无主机名的设备顶替显示前一台设备的名字，列表重名）。修复是把初始化移入每轮循环开头。

判断规则：弹窗预填“当前值”必须在打开时刻现场获取，禁止复用初始化期捕获的变量。循环内使用的临时变量在每轮迭代开始处初始化。review 快查法：看到 `val x = getX()` 与 `setOnClickListener { 用 x }` 相邻就问“x 会中途变吗”。“缺数据时沿用上一次的值”是最隐蔽的展示错误——不崩溃不抛异常，只有把两条数据放在一起对比才能发现。

**Q21: [learning] 界面关闭/开关关闭后仍显示分组标题与入口，条件渲染要覆盖哪些部分？（SIR-6742）**

条件渲染的包裹范围必须覆盖“标题、内容、空态”三件套，只保护内容是常见的半截防护。SIR-6742 中蓝牙关闭后界面仍显示“可配对设备”分组标题与刷新入口——可用设备条目有开关状态判断保护，但分组标题的添加在判断之外无条件执行。同时空态文案的可见性在函数末尾按“设备为空”统一计算，关闭态下列表为空反而把空态提示隐藏了，出现“有标题、无内容、无空态”的破碎 UI。修复是把标题项、内容条目、空态可见性全部收进同一个开关状态分支，关闭时固定显示空态反馈。

判断规则：列表组装逻辑里分组标题必须与组内容同条件渲染，空态判断与条件分支的优先级要理清（先判状态分支再算空态）。测试上，该页面还有开关状态与“用户主动关闭”标志的双状态叠加，测试矩阵要交叉覆盖开/关、扫描中、主动关闭的组合。

**Q22: [learning] 派生 UI 状态（拨号键可用性）的计算逻辑完全正确，却因刷新调用挂错位置、漏挂一条路径而永久置灰，刷新时机该怎么核对？**

派生状态的刷新必须挂在所有会改变源数据的路径上，且挂点在数据更新之后——挂早了读到旧值，漏了路径则永不刷新。计算逻辑正确不代表状态正确，刷新时机链才是派生状态唯一的失效面。

某车机项目 SIR-3247 案例（B 级，必现）：双路通话挂断一路后拨号盘拨打键仍置灰，无法再次用拨号盘发起新通话。拨号键可用性由 `InCallServiceImpl.updateDialPadButtonState()` 计算——读 `UiCallManager.get().getCalls().size()`，按 `isShouldDisableCallBtn(2)`（通话数 ≥2 置灰）经 RxBus 发 `DIAL_PAD_ENABLE_STATE_CHANGED`。计算本身没错，错在两处时机：

1. `onCallAdded` 里刷新调用被放在回调分发**之前**——第二路进入时统计到的还是 1 路，按钮被误判为可用。
2. 挂断与异常号码呼叫失败走的是**通话状态变更**路径，该路径上完全没有刷新调用——通话对象已减少但按钮状态事件从未补发，置灰保持。

修复是把刷新移到数据更新之后，并给遗漏的状态变更路径补上（节选自真实 diff）：

```diff
--- application/BTPhone/.../telecom/InCallServiceImpl.java (onCallAdded)
-        // 【新增】更新拨号盘按钮状态
-        updateDialPadButtonState();
-
         // 【新增】在注册回调前，先验证蓝牙HFP状态
         boolean isHfpConnected = UiBluetoothMonitor.get().isHfpConnected();
         ...
         for (Callback callback : mCallbacks) {
             callback.onTelecomCallAdded(telecomCall);
         }
+        // 【新增】更新拨号盘按钮状态
+        updateDialPadButtonState();
--- (通话状态变更处理处，状态转换/通知中间件之后)
             LogUtils.e(TAG, "==================================================");
         }
+        updateDialPadButtonState();
         Constants.setCallState(state);
```

该案例还提供一个“以 diff 为准”的实例：提交消息写“修改通话数量计算”，但 diff 并未改动数量计算本身（仍是 `getCalls().size()`），实质是补刷新时机——复盘时若只读提交消息会把经验归错方向。边界：补刷新路径时要枚举全部状态来源（本例是“新增通话”与“通话状态变更”两条），只补被测试发现的那条会留下同类缺口。把刷新收敛为状态变更后的统一出口，比散在各回调里更不容易漏。判断规则：派生状态（按钮可用性、置灰、文案）出问题时，先验证计算输入是否正确，再沿“谁改了这个输入”逐条核对刷新是否挂在其后。“计算对但表现错”是刷新时机的特征信号，不是计算逻辑的信号。

**Q23: [learning] 缓存列表与系统真实状态失步导致列表错乱，两层防御分别是什么？（SIR-8398、SIR-8671）**

第一层是用权威源做自愈校验，第二层是展示入口去重防御：

- **权威源自愈**（SIR-8398）：已配对列表来自本地缓存，缓存与系统绑定状态失步漏掉一台设备时，“可用设备 = 全部扫描结果 - 已配对地址”的差集过滤就会把该设备错误地留在未配对区。修复是在组装入口用设备自身的 `isBonded`（直读系统栈，权威）做二次校验，把漏掉的已配对设备归位。
- **入口去重**（SIR-8671）：扫描缓存偶发含同一 MAC 地址的重复实例（移除后重扫、广播与缓存刷新竞态写入），原样组装即出现两个同名条目。修复是在组装入口用 `HashSet.add` 的返回值做首次可见判定，按地址去重。

判断规则：不信任单一缓存来源，凡“缓存 + 差集/过滤”的结构，源缓存漏一条或多一条，错误结果就会被原样渲染。先问“数据什么时候可能漏一次、写两次”，再决定修源头还是修展示——展示层防御性价比高，但权威源校验才是正确性保证。SIR-8398 权威源自愈的实现（节选自真实 diff）：

```diff
--- BluetoothFragment.kt：列表组装入口用系统侧 isBonded 归位漏掉的已配对设备
+            val healedAddresses =
+                mPhonePairedDevices.mapNotNull { it.bluetoothDevice?.address }.toSet()
+            availableDevices
+                .filter {
+                    it.isBonded && it.deviceType == BluetoothDeviceType.CELL_PHONE &&
+                            it.address !in healedAddresses
+                }.forEach { mPhonePairedDevices.add(MultiBluetoothDevice(1, it)) }
```

偶现的列表错乱优先怀疑系统回调与本地缓存两个数据源失步的窗口期。

**Q24: [learning] 底层上报的设备名可能为空时，列表为什么渲染出空白条目、且与重复条目在界面上无法区分，渲染层要怎么处理？**

“应该有值”的字段在底层异常时照样为空，渲染层必须做多级兜底（name → address → “未知设备”）。而数据层没有兜底标识时空名条目与重复条目在界面上是同一种表现，用户和测试都无法区分。

某车机项目 SIR-1386 案例（B 级，高概率 40%~80%）：热点反复开关后已连接设备列表出现重复条目与空名条目。根因在数据侧——底层 wifi AP 服务上报的客户端记录里 `name` 字段可能为空（底层未返回设备名），`HotspotAdapter.convert()` 直接 `holder.setText(R.id.tv_name, item.name)`，空名渲染成空白。同时数据层没有任何兜底标识，空名条目与重复条目在界面上难以区分（节选自真实 diff）：

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/adapter/HotspotAdapter.kt
-        holder.setText(R.id.tv_name, item.name)
+        LogUtils.d("HotspotAdapter", "convert: $item")
+        holder.setText(R.id.tv_name, if (TextUtils.isEmpty(item.name)) item.address else item.name)
```

同笔提交还删除了从未被赋值的 `tv_address` 副控件（adapter 不再设置其颜色，布局同步删除，避免悬空 id），并补充 AP 状态广播里的 `WIFI_AP_FAILURE_REASON` 日志便于底层定位。回退到 MAC 地址后，条目永远有唯一可辨识文本，空名不再出现，MAC 天然区分条目也顺带缓解“看起来重复”的迷惑。

边界两条：MAC 地址直接展示给终端用户可读性差且涉及隐私展示习惯，产品上更宜显示为“未知设备（MAC 后 4 位）”。该单据现象里的“设备重复”在本次 diff 中没有对应的去重逻辑修改，重复条目的根因仍在数据层、本次未闭环——以 diff 实际内容为准，不把未修的半边症状算作已解决。判断规则：列表渲染对“业务上必须有值”的字段一律做多级兜底而不是直接 `setText`。数据层应保证每条记录有可辨识的稳定标识，兜底展示与去重是两层独立防御，前者保证可读、后者保证唯一。

**Q25: [learning] 多进程共用的单例工具类对文件做了内存缓存，另一进程写入后本进程读到旧值，如何最低成本修复？根治方向是什么？**

“单例 + 内存缓存”一旦被多个进程使用，缓存一致性就成了需求而不是实现细节——跨进程读写点必须重读磁盘或走跨进程通知。SIR-3248 中座椅名称存于公共组件 SeatUserManager（内存 cache 加 JSON 落盘），3D 车模进程改名后 Setting 进程的 cache 不会自动失效，车控页读到的仍是旧名，而页面又只在初始化时加载一次。修复两层：读路径每次调用前强制重读磁盘，页面层补一次进页刷新（节选自真实 diff）：

```diff
--- component/CommonTools/src/main/java/.../manager/SeatUserManager.kt
     fun getSeatNamesFromJson(userId: Long): Array<String> {
         LogUtils.d(TAG, "getSeatNamesFromJson userId=$userId")
+        // 重新从磁盘加载缓存，避免多进程写入后读取到旧数据
+        cache = loadFromDisk()
         val defaultNames = arrayOf("位置1", "位置2", "位置3")
```

再在车控页 `onResume` 里补调 `loadSeatPositionNames()`，保证每次进入页面触发一次新读。读取频率低（进页、改名时）时打磁盘的开销可接受。边界：这只是读路径止血——写方仍不通知读方，两进程同时读写仍有竞态窗口，后续提交继续在写路径以广播通知加固。读写频繁的场景应改跨进程通知或收口到单一进程。判断规则：跨进程共享的数据结构凡有内存副本，就要回答“另一进程写了谁来失效”。页面数据可能被外部（其他进程/入口）修改时，onResume 刷新是最低成本兜底。

**Q26: [learning] Kotlin 循环里命中条件就写裸 return，为什么会让“首次配对的耳机被显示成后排”？暴露了哪两类混淆？**

裸 `return` 退出的是整个函数而非当前循环——把“设备已在配对列表”（存在性）误当成“角色已分配完毕”（完备性），首次配对的设备回调到达时往往已登记进配对列表，命中存在性检查后整体返回，后面“前排空闲给前排、否则给后排”的赋值逻辑永远走不到，role 缺省被渲染成“后排”。修复区分两种退出意图：仅当前后排角色都已占用才整体返回，否则 `return@forEach` 只跳过本条、继续走赋值（节选自真实 diff）：

```diff
--- BluetoothAnwFragment.kt（角色分配）
             BtAnwManager.getInstance().mPairedDevices.forEach {
                 if (it.getMacAddress() == address) {
-                    return
+                    if (isHasFront && isHasBack) return
+                    return@forEach
                 }
             }
```

两类混淆分开治：其一，`return` 与 `return@forEach` 一字之差退出范围天壤之别，循环内提前返回必须显式确认想退出的是循环还是函数。其二，“存在于集合”不等于“初始化完成”，存在性检查与状态完备性检查要分开判断。另有一层防御要补：role 的缺省渲染值本身就是坑（缺省值隐含了“后排”业务含义），缺省态应有显式的“未分配”展示或立即分配兜底。判断规则：review 看到 forEach 内的裸 `return` 就问一句“想退出的范围是什么”。设备属性的缺省渲染值禁止隐含业务语义。

**Q27: [learning] Android 页面用 pending 状态校验异步回显时，怎样避免条件反转与生命周期重放造成误判？**

把期望值与实际回显分别建模，明确规定“相等表示成功、不等表示失败”，并让 pending 只对应当前一次请求。否则条件写反或旧请求状态残留，会把成功判成失败，或把旧回显套到新操作上。某车机项目 DrivingFragment 的“暂存目标状态 + LiveData 回显比较”实现曾先后暴露这两种缺陷。

第一笔提交 `e2ee6731` 将失败提示放在 `isStateMatched` 为真时，造成匹配成功反而弹失败提示、不匹配却不提示。下一笔 `11d164b8` 将条件改为不匹配才进入失败分支：

```kotlin
if (!isStateMatched) {
    showToast(R.string.slip_mode_switch_open_fail_tip)
}
```

这个判断成立的前提是 `isStateMatched` 已按“实际回显与本次 pending 目标相等”计算。若变量语义相反，单改取反符号仍会错。验收应覆盖相等与不相等两种输入，并确认只有失败路径提示。

第二个缺陷出现在页面重新变为活跃时：Lifecycle-bound LiveData observer 可在重新活跃后再次收到其当前版本尚未消费的最新值。若此时旧 pending 仍在，旧值可能被当成本轮操作回显。页面可见不等于必然重建，是否重放取决于 observer 的生命周期状态及版本消费情况，因此不能把“回到页面就一定收到旧值”当成固定时序。该案例在 `onStart()` 清空 `drivingModeStateTemp`、`slipModePendingState` 和 `extremeRangePendingState`，让新一轮回显比较不沿用已结束操作的待确认值。

对这类待确认状态，可按以下顺序设计与验证：

1. **建立请求上下文**：发起操作时记录目标值。如可能并发或连续发起请求，增加 requestId 或明确只允许单个 pending。
2. **消费匹配回显**：收到状态后只与当前请求比较，成功、失败、pending 为空及超时都要形成互斥且完整的结果分支。每个请求的 pending 只能消费一次，结束后立即使其失效，避免旧值再次命中。
3. **处理生命周期边界**：界面停止、重新活跃、销毁和重建时，确认 pending 是应保留、取消还是恢复。不要无条件清空后丢掉仍有效请求的回执。
4. **覆盖真值与时序**：至少测试匹配、不匹配、pending 为空、请求结束后重复回调，以及页面离开后返回时的回调。

清空 pending 是一种策略而非通用修复：它可以防止过期请求污染新会话，但也会放弃清空之后到达的有效回显。若请求必须跨页面状态持续等待，应使用请求标识或独立请求状态管理来关联回执，并明确超时和取消语义。

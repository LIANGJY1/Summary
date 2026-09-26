# Kanzi 双端状态同步：状态残留、乐观更新与能力差异

> 学习资料（文章模式沉淀）。主线：Android 宿主与 Kanzi 3D 车模渲染端的状态同步规则——纯被动端的责任划分、组合状态成对复位、重复上报去重、乐观更新只向事实源对齐、字形与枚举能力集以最弱端为准、状态副作用收敛到状态 entry。源文档：`Summary/project/yadi/git提交与缺陷分析` 00_主报告 §5 模式二/三、EnergyManagement.md、Launcher.md 及逐commit详析深案例（缺陷单号 SIR-xxxx 为溯源锚点）。Q 序列即结构，供 atlas 同源直读。2026-09-26 修订：补代码级讲解与深案例覆盖。

**Q1: Kanzi 3D 车模作为纯被动渲染端，状态同步的基本责任如何划分？**

Kanzi 不自行推演或恢复状态，Android 侧是唯一写入口——凡主动下发过的状态变更（尤其是清零）必须有对应的再下发时机，初始化时要主动全量推送当前状态，否则渲染端停留在最后一次收到的值上。

某车机项目 SIR-326 案例：充电枪超时时 Android 侧清零 `Charging_Gun_State=0` 并下发，超时恢复分支没有任何处理；超时期间枪状态信号已恢复"已连接"，却无人再推一次，车模永久显示未连接。修复（提交 `3bcae00f`）在超时信号恢复分支补上"重读再下发"：

```diff
@@ KanziSignalMapping：ENERGY_MCU_SEND_MPU_TIMEOUT_REPORT_0C12FF04 处理
                 mAcChargingGunStatus = 0;
                 mDcChargingGunStatus = 0;
                 updateChargingGunStateToKanzi();
+            } else {
+                refreshChargingGunStateFromVehicle(); // 超时恢复：重读 4010/4011 实际枪状态再下发
             }
```

恢复分支经 `mVehicleService.getIntProperty` 回到 VHAL 重读 AC/DC 枪状态、刷新缓存后调 `updateChargingGunStateToKanzi()` 重下 Kanzi，车模随即恢复连接显示——恢复时不信被清零过的缓存、回到数据源，正是"主动清零义务"的对称面。配套的初始化义务见 SIR-3248 案例：座椅名称修复时专门补了"Kanzi 初始化时主动把当前 3 个名称发进去"，避免进入车控车设时状态落后。

边界：被动渲染端不会崩溃报错，只会静默显示旧值或残留值，缺陷表现全部出现在"另一端"；排查时先审 Android 侧的写入时机清单——进入、退出、恢复、初始化四类时机都应有对应下发，缺一类就是一类隐患。

**Q2: SIR-1539 中，由 Gear 与 D_Desktop 两个属性联合表达的 D 档驻车界面，切回 P 档时为什么必须把两个属性成对复位？**

组合状态只还原其中一个属性必然残留非法组合——Gear=0 且 D_Desktop=1 时车模交互持续锁死；退出路径必须把所有相关属性复位，且同一状态的多条下发路径要统一维护。

某车机项目 SIR-1539 案例：切回 P 档时两条下发路径（`KanziSignalMapping` 的真实档位信号处理、`KanziDataSourceManager` 的桌面档位广播）都只发 `Gear=0` 不清 `D_Desktop`，Kanzi 残留在 Gear=0、D_Desktop=1 的非法组合，车模不可滑动。真实档位信号路径的修复（提交 `444b41ab`）：

```diff
 signalHandlers.put(CarPropertyIds.ENERGY_PCU_ACTUALGEAR, event -> {
+    int gear = ((Number) event.getValue()).intValue();
     mHandler.postDelayed(() -> {
-        sendToKanzi(KanziType.CarModel.GEAR, event.getValue());
-    }, (int) event.getValue() == 1 ? 0 : 500);
+        if (gear == 0) {
+            sendToKanzi(KanziType.CarModel.D_DESKTOP, 0); // P 档先复位从属状态
+        }
+        sendToKanzi(KanziType.CarModel.GEAR, gear);
+    }, gear == 1 ? 0 : 500);
 });
```

桌面广播路径（`ACTION_PANORAMA_DESKTOP`）做了同构修改；`D_DESKTOP=0` 先于 `GEAR=0` 下发让 Kanzi 侧复位顺序确定，D 档仍只更新 Gear、不动 D_Desktop，D 档驻车界面逻辑不受影响。

边界：复位顺序要有约定（先复位从属状态再切主状态）并沉淀为协议注释；两条路径逻辑重复意味着新增档位要同步改两处，理想做法是收敛到单一入口防止再漏；复位经 `postDelayed` 延迟执行时，窗口内再次切档仍可能短暂错序。判断某属性是否属于"必须成对复位"的依据是渲染端状态机：它的落位条件由哪些属性联合决定，退出时就复位哪些。

**Q3: SIR-3241 中一次物理点击导致渲染端以约 386ms 间隔重复上报同一按键事件时，防抖窗口应如何取值？**

防抖窗口必须落在"重复噪声间隔"与"人类正常双击间隔"之间——小于前者挡不住噪声，大于后者吞掉真实操作；取值要靠日志量化，不靠拍脑袋。

某车机项目 SIR-3241 案例：日志量化出一次点击附近 Kanzi 会重复上报 `Button.Handlebar value=1`，间隔约 386ms，旧逻辑对每次上报都推进一档，第一次点击切到预期档位后，重复上报又多推一档。修复（提交 `b7addf45`）增加 600ms 防抖：

```diff
@@ KanziType.Button.HANDLEBAR 点击处理
+    long now = System.currentTimeMillis();
+    if (now - mLastHandlebarClickTimestamp < HANDLEBAR_CLICK_DEBOUNCE_MS) { // 600ms 防抖窗口
+        break; // 窗口内判为重复上报，直接忽略
+    }
+    mLastHandlebarClickTimestamp = now;
     int previousHandleLevel = mHandleHeatLevel;
```

600 大于 386ms 的噪声间隔、小于正常双击间隔，窗口内的重复上报直接忽略，一次物理点击只推进一档——档位推进的驱动源只剩真实点击。

边界：防抖只覆盖被量化的那一个按钮，同类按钮（如座椅加热）的重复上报问题要同样排查；防抖会延迟合法的快速连续操作，窗口取值本质是噪声间隔与操作速度之间的折中；重复上报的间隔可能随渲染帧率或负载漂移，量化数据要标注采集条件。

**Q4: SIR-6283 中乐观更新叠加延迟反馈兜底时，车端真实回调到达后必须做什么？反馈校验失败时状态应对齐到哪个值？**

真实回调到达时必须撤销未执行的兜底任务（`removeCallbacks`），否则兜底会用过期数据反杀新状态；校验失败时状态应对齐到车辆实际反馈值（事实源），而不是回滚到点击前的本地缓存。

某车机项目 SIR-6283 案例：座椅加热 1 秒延迟校验读到实际反馈 1 而请求是 2，旧逻辑判定"设置失败"，回滚到点击前的 0 并向渲染端写 0——真实状态明明是 1 档，却把正确值也覆盖掉；这是"请求不等于反馈就回滚"加"回滚目标选了过期的本地猜测"的两步连环错误。修复（提交 `59ee74b9`，+8/-10）删除回滚分支：

```diff
-    private void seatHeat(int nextSeatHeatLevel, int previousSeatHeatLevel) {
+    private void updateSeatHeatFromVehicle(int requestedSeatHeatLevel) {
         int actualSeatHeat = mVehicleService.getIntProperty(CCU_SETSEATHEATSWREQ, 0);
-        if (actualSeatHeat != nextSeatHeatLevel) {
-            mSeatHeatLevel = previousSeatHeatLevel; // 请求≠反馈：回滚到点击前的旧缓存
-        } else {
-            mSeatHeatLevel = actualSeatHeat;
-        }
+        mSeatHeatLevel = actualSeatHeat; // 无条件向车辆反馈（事实源）对齐
     }
```

删除 `previousSeatHeatLevel` 后，第二次点击的校验即使读到反馈 1 不等于请求 2，状态也收敛到车辆真实值 1，再同步渲染端显示一档。同项目 SIR-3241 案例中，1000ms 延迟反馈任务会用车端回调之前的旧请求结果回滚档位，修复在收到真实回调 `CCU_SETHANDLEHEATSWREQ` 时 `removeCallbacks` 移除该任务，兜底的反杀通道被关死。

边界：以车为准的策略在反馈异常（读不到、返回默认 0）时会把 UI 拉回错误值，不再有本地兜底，这是策略的固有取舍；延迟校验窗口内用户继续操作会产生竞态，校验触发时的"世界"已不是发起请求时的"世界"，比对必须基于当前事实而非发起时的快照。

**Q5: SIR-2178 中车控按钮"等底层回执再刷新 UI"会有什么后果？乐观更新加异步校验的完整结构是什么？**

完全依赖底层回执时，回读不及时或丢回调会让点击毫无响应；标准结构是点击立即用目标值更新本地缓存与渲染端，异步回执降级为校验，且延迟回调用具名 Runnable 持有以便取消。

某车机项目 SIR-2178 案例：把手加热与座椅加热点击后只向车端下发，本地档位与 Kanzi 状态要等 1 秒延迟回读才更新，回读丢失则渲染端永远收不到状态更新，表现为点击无响应。修复（提交 `72c8c592`）改为乐观更新结构：

```diff
@@ KanziType.Button.HANDLEBAR 点击处理
     int nextHandleLevel = getNextHandleHeatLevel(mHandleHeatLevel);
+    mHandleHeatLevel = nextHandleLevel;
+    updateHandlebarStateToKanzi(); // 点击立即用目标档位刷新渲染端
     sendToVehicle(CarPropertyIds.CCU_SETHANDLEHEATSWREQ, nextHandleLevel);
+    if (mHandleHeatFeedbackRunnable != null) {
+        mHandler.removeCallbacks(mHandleHeatFeedbackRunnable); // 连点先撤销旧校验任务
+    }
+    mHandleHeatFeedbackRunnable = () -> {
+        // …（节选自真实 diff：1 秒后回读校验，不符则回滚并再次刷新）
+    };
+    mHandler.postDelayed(mHandleHeatFeedbackRunnable, HANDLEBAR_FEEDBACK_DELAY_MS);
```

Kanzi 马上收到目标档位、界面即时响应；延迟回执降级为校验——不符则回滚并再次刷新，连点时 `removeCallbacks` 防止旧回执覆盖新状态；座椅加热补齐了同构处理与 `getNextSeatHeatLevel` 循环档位（关→3→2→1→关）。

边界：这条案例早期的"校验不符回滚到点击前值"设计，后来在同类缺陷 SIR-6283 中被修正为"对齐车辆反馈值"——说明乐观更新结构里的校验分支只应做一件事：向事实源对齐；回滚目标是本地猜测的写法会被后续缺陷推翻。乐观值与实车短暂不一致是该模型的固有窗口；匿名 `postDelayed` 无法取消，是竞态隐患的直接来源。

**Q6: SIR-2860 中"点击后立即读属性回刷 UI"为什么是反模式？等待车端确认的正确结构是什么？**

车端执行有滞后，点击后立刻读属性读到的必是旧值，会把刚操作的目标覆盖回去；正确结构是乐观高亮加 pending 窗口——窗口内过滤不匹配的旧值回调，超时后按真实值校正。

某车机项目 SIR-2860 案例：座椅记忆选中位置 2 时点击位置 3，立即读车端属性读到旧值 2 回刷，位置 3 的高亮被覆盖；车端迟到的旧值回调同样会无条件刷 UI。修复（提交 `3acbee01`）分四层，前三层在点击与回调路径：点击后先把目标位置同步给渲染端使其立即高亮；记录 pending 目标位置 `mPendingSeatMemoryPosition`；pending 期间 signalHandler 丢弃与目标不符的回调：

```diff
@@ CCU_SEATPOSITIONMEMORYRECALL 信号回调
+    if (mPendingSeatMemoryPosition != 0 && seatMemory != mPendingSeatMemoryPosition) {
+        return; // pending 期间丢弃与目标不符的旧值回调
+    }
```

第四层是超时兜底：超时后重读真实车端信号，不匹配则按实际值校正回退，保证 UI 最终与车况一致。修复前先把三个记忆位按钮重复的延迟回调代码收敛成一个 `handleSeatMemoryClick(int)` 处理函数，再修 bug，避免改三处漏一处。

边界：超时窗口若小于车端实际召回耗时，正常成功场景也会先走一次"校正"并短暂回退显示，好在 pending 清空后到达的真实回调仍会被接受；对"写后读"型信号要有期待值比对，不能对任何回调无条件刷 UI。

**Q7: SIR-6343 中 Android 侧显示正常的文本，为什么在 Kanzi 3D 车模上可能不渲染？跨端文本能力差异应在哪些环节治理？**

能力集以最弱渲染端为准——Android 输入框有系统 Emoji 字体回退而 Kanzi 的 MiSans 字体不含 Emoji 字形，字符串传输正常，渲染端找不到字形即整体不显示；治理要在输入、保存、读取、发送四个环节布防，而不是等下游不显示再查传输。

某车机项目 SIR-6343 案例：座椅位置命名为表情 🐒 后，3D 车模的座椅记忆名称不显示。修复四环节（提交 `5373410a`）：输入框挂 `InputFilter` 实时拦截（用户根本输不进去）；保存前清洗并拒绝纯 Emoji 名；读取历史数据时清洗，治理已存在的脏数据；发送 Kanzi 前最后过滤：

```diff
@@ KanziDataSourceManager.sendSeatNameToKanzi()（发送环节兜底）
     String key = SeatNameStore.getKanziTextKey(position);
-    kanziManager.setValue("", key, seatName);
+    String safeSeatName = SeatNameStore.sanitizeSeatName(seatName);
+    if (safeSeatName.isEmpty()) {
+        safeSeatName = SeatNameStore.getSeatName(position); // 清洗后为空回退默认名
+    }
+    kanziManager.setValue("", key, safeSeatName);
```

发送前兜底保证渲染端拿到的永远是 MiSans 可渲染的文本。

边界：过滤按 Unicode code point 处理，覆盖代理对、ZWJ 连接符、肤色修饰符与样式选择符等组合成分，否则删一半留下残字；Emoji 区段枚举是白名单式边界判断，Unicode 新增区段需补表；中文、英文、数字、空格与普通标点全部保留，正常命名不受影响。存量脏数据不会因新校验消失，读取路径的清洗是必须项而非可选优化。

**Q8: SIR-6343 的 Emoji 清洗中，为什么必须按 Unicode code point 而不是按 char 处理？**

Java 的 char 是 UTF-16 代码单元，而 Emoji 由代理对、ZWJ 连接符（U+200D）、肤色修饰符、样式选择符（U+FE0F）等组合而成，按 char 逐个过滤必然漏删，或把组合 Emoji 拆成半个残字。

某车机项目 SIR-6343 案例的清洗实现：`sanitizeSeatName` 按 code point 遍历、`Character.charCount` 步进，命中 Emoji 区段或组合成分则跳过，其余字符保留：

```java
public static String sanitizeSeatName(String name) {
    // …（节选自真实 diff：name 为 null/空时返回空串的守卫略）
    StringBuilder result = new StringBuilder(name.length());
    for (int offset = 0; offset < name.length();) {
        int codePoint = name.codePointAt(offset);
        offset += Character.charCount(codePoint); // 代理对一次步进 2，不拆出半个残字
        if (!isEmojiCodePoint(codePoint)) {
            result.appendCodePoint(codePoint);
        }
    }
    return result.toString();
}
```

`isEmojiCodePoint` 以区段白名单判断（补充平面主 Emoji 区、符号区间、旗帜区间、肤色区间）外加 ZWJ 与变体选择符的单点判断；输入过滤、保存、读取、发送四个环节共用这一个实现。

边界：区段枚举是静态白名单，未来 Unicode 新增 Emoji 区段需要补表；渲染端若升级字形库开始支持新符号，过滤会显得过严，能力边界变化时两端要同步评估；判断"某个码位是否该删"时以渲染端能力为唯一标准，不以 Android 侧能否显示为准。

**Q9: SIR-8124 与 SIR-1662 中，给渲染端下发的枚举状态值和功能配置字，为什么要与渲染端的状态表、配置清单逐一核对？**

渲染端状态机只能落在双方约定过的值上，越界值往往静默失效——不崩溃、不报错，只是该显示的元素消失；功能开关靠配置字显式下发，不传就按渲染端默认能力执行。

某车机项目 SIR-8124 案例：未安装尾箱被发故障态值 3，而渲染端故障态素材只有"已安装"版本，状态机无法落位，尾箱"更改"按钮消失。修复（提交 `8e793ae2`）维护"最后一次有效安装态"缓存，异常场景按缓存决策：

```diff
@@ THREE_D_MODEL_PCU_REARBOXSTATUS 处理器
     mRearBoxStatus = (int) event.getValue();
+    if (mRearBoxStatus == 0 || mRearBoxStatus == 1) {
+        mLastValidRearBoxStatus = mRearBoxStatus; // 缓存只接受有效值，故障/超时不污染
+    }
@@ 超时/故障路径的统一出口
+    int rearBoxState = (mLastValidRearBoxStatus == 0) ? 0 : 3;
+    sendToKanzi(KanziType.CarModel.ECO_TRUNK_STATE, rearBoxState); // 未安装发 0，不发越界的 3
```

未安装时异常场景发 0 保持未安装态，已安装才发 3，渲染端状态机都能落位。同项目 SIR-1662 案例：中低配车模不应有座椅调节功能，根因是 Android 侧没有把座椅调节配置字传给 Kanzi，修复补传配置字。

边界：这类问题的排查顺序是先对齐双方状态机与配置清单，再怀疑通信链路——传输正常而值越界是最常见形态；渲染端新增状态值或素材时，Android 侧的映射表要同步评审，反向亦然。

**Q10: SIR-6711 中，状态机"进入某状态的副作用"散落在各事件迁移路径有什么风险？应如何组织？**

只在一条事件路径上做的副作用调用，换条路进入同一状态就丢失；进入某状态必须做的通知或初始化应收敛为状态的 entry 动作，覆盖所有迁移边。

某车机项目 SIR-6711 案例（与 SIR-7020 同一提交修复）：进入 D 档暂态桌面时，SystemUI 必须通知 Launcher 置 Kanzi 的 `D_Desktop=1`，车模才渲染；旧代码只在四指滑动路径做了该通知，车速降 0 触发解锁、导航异常退出等其他进入路径都没有调用，从这些路径进入后桌面白底、车模不渲染。修复（提交 `19103951`）在所有进入该状态的迁移统一补上通知：

```diff
@@ PageStateMachine.kt：Event.DriveTouchUnlocked 迁移
     State.S3_Android_Drive to { _: DataContext ->
+        // 进入暂态桌面需通知 Launcher 置 Kanzi D_Desktop=1，否则桌面白底、车模不渲染
+        mainHandler.postDelayed({ notifier.enterFiveFingerCapture(1) },
+                FOUR_FINGER_SWIPE_INTO_S3_DELAY)
         notifier.showAndroidDrivePage()
     }
```

延迟常量提取为 `FOUR_FINGER_SWIPE_INTO_S3_DELAY`，与既有四指路径保持一致。

边界：状态机修复要枚举"到达同一状态的所有迁移边"逐边检查，遗漏的典型测试表现是"某条入口路径正常、其他路径异常"；副作用收敛到 entry 后，新增迁移边天然继承该动作，这正是收敛的价值所在。

**Q11: SIR-1656 中，界面选中态同时有本地点击与信号回灌两个来源时，派生字段如何维护才不失步？**

派生字段必须跟随所有能改变选中态的写入源——点击路径更新的字段，信号回灌路径也要同步更新，漏一处即失步；理想做法是单一选中态存储，其余状态全部派生。

某车机项目 SIR-1656 案例：重命名操作的目标取自"最近活跃记忆位"字段 `mLastActiveMemoryPosition`，它只在用户点击时更新；车辆信号回灌 `CCU_SEATPOSITIONMEMORYRECALL` 把界面高亮到记忆 2 时，该字段仍停在默认值 1，选中记忆 2 执行重命名实际改写了记忆 1 的名称。修复（提交 `5c8c36f1`）在信号回灌刷新高亮时同步更新该字段：

```diff
@@ updateSeatMemoryRecallToKanzi()
     int blueLineState = (seatMemory >= 1 && seatMemory <= 3) ? seatMemory : 0;
+    if (blueLineState != 0) {
+        mLastActiveMemoryPosition = blueLineState; // 信号回灌路径同步派生字段
+    }
```

反馈值无效（0）时保持原值不清空，避免误抹选中态。该缺陷实际经两笔提交才收敛：第一笔（`1efa1a63`）只在本地点击路径记录该字段，纯信号高亮场景仍会带偏重命名目标，第二笔才补齐信号回灌路径——派生字段的写入源要逐个清点，清点不全就会分次返工。

边界：若业务上"信号高亮"不应等同"用户选中"，强绑定会把两个语义合并，需要产品确认；回灌信号绕过本地交互直接改 UI，code review 时要专门排查"信号路径是否也更新了所有关联缓存"；界面显示与操作目标使用两个真相源，漂移只是时间问题。

**Q12: SIR-3248 中，多进程共享数据加进程内缓存的结构里，写操作为什么会静默失败？缓存应放在什么定位？**

进程内缓存是本进程上次加载的快照，其他进程新建的数据不在快照里，写路径按缓存定位失败就提前退出，写入无声丢失；缓存的正确定位是纯加速而非事实源，读写操作前都应重载磁盘。

某车机项目 SIR-3248 案例（同一缺陷的第三笔修复）：Launcher 进程写座椅名时，该用户数据由 Setting 进程创建，Launcher 的内存缓存里没有这条记录，定位函数返回 -1 后方法直接 return，磁盘上永远是旧数据；此前两笔修复只给读路径加了磁盘重载，写路径漏掉，形成"读得到新数据、写不进新数据"的半修复状态。最终修复（提交 `74777571`）在写入口定位用户前先从磁盘重载：

```diff
@@ SeatUserManager.kt
     @Synchronized
     fun setSeatPositionName(position: Int, name: String) {
+        cache = loadFromDisk() // 写前重载磁盘，避免用本进程过期快照定位用户
         val userId = SettingsUtils.getGSettingLong(CACHE.USER_ID)
         val start = getUserStartPosition(userId)
         if(start==-1){ return}
```

重载发生在 `@Synchronized` 临界区内，Setting 进程创建的用户记录能够被找到，写入不再无声丢失。

边界：读写都重载会放大 IO，只适用于低频写场景；多进程同时写仍无文件锁，后写覆盖先写的风险要靠"写入频率低、目标区块不同"的业务前提兜住；定位失败的早退分支至少要打日志，否则写失败不可见，只能靠终态现象倒查。

**Q13: SIR-4593 中，Android 侧与 Kanzi 渲染动画交接时，过早暂停渲染会怎样？**

Android 侧过早暂停渲染会让切换动画没播完就被截断，表现为动效错误；需要给动画留出播放窗口再暂停渲染。

某车机项目 SIR-4593 案例：3D 驻车桌面进入仪表、仪表退回 3D 驻车桌面的动效错误，缺陷库根因之一是安卓侧过早暂停渲染（另一根因是仪表新版动效未开发完成，属双端协作进度问题）。修复（提交 `2f727b27`）把"立即停"改为区分场景的延迟停：

```diff
@@ checkAndPerformRenderState()
+    ThreadUtils.getMainHandler().removeCallbacks(mRenderStopRunnable);
     if (!mIsForeground || mCurrentGear == 1 || mCurrentGear == 2) {
-        setRenderStop();
+        if (mIsForeground && mCurrentGear == 1) {
+            ThreadUtils.getMainHandler().postDelayed(mRenderStopRunnable, DELAY_D_GEAR_STOP_MS);
+        } else {
+            setRenderStop();
+        }
```

"前台 + D 档"延迟 600ms 暂停渲染，给车模切换动效留出播放窗口；后台或 R 档仍立即停止，省电策略不变；入口先 `removeCallbacks` 再决策，防止 D→P→D 快速抖动时旧的延迟停止任务在新一轮恢复渲染后又把渲染停掉。

边界：固定延迟是经验时序，动画时长调整时需同步修改；另一侧动效未就绪时，单侧修复无法掩盖双端进度差，联调排期要对齐；此类"交接窗口"参数应集中定义，避免多个迁移各自维护一份数值。

**Q14: 面对"3D 车模某状态不刷新或残留"类缺陷，系统化的排查顺序是什么？**

按"先对齐状态机、再查写入路径、最后查时序与驱动"的顺序排查：确认双方状态表逐一对应、枚举 Android 侧所有下发路径、检查进入与退出分支是否对称、确认刷新驱动只剩事实源、核对被动端的再下发与初始化时机。

五个步骤各有真实案例支撑：第一步对齐值域——渲染端状态机能否落位该值，某车机项目 SIR-8124 案例中未安装尾箱被发故障态值导致按钮消失；第二步枚举写入路径——同一组属性有几条下发路径、是否只修了一条，SIR-1539 案例中真实档位信号与桌面广播两条路径都要补复位；第三步检查对称性——置位分支写了什么、复位分支是否恢复，SIR-326 案例中超时清零后无恢复分支，车模永久显示未连接；第四步检查驱动源——乐观更新、延迟兜底、真实回调并存时是否只向事实源对齐，SIR-6283 案例中回滚到点击前值把正确状态覆盖掉；第五步核对时机——初始化全量推送、页面恢复重读、进入状态的副作用收敛到 entry。

边界：这套顺序的前提是渲染端纯被动；若渲染端自身有逻辑（内嵌脚本），还要审它的事件处理。传输链路一般最后才怀疑——传输正常而值越界、值正确而时机缺失，才是这类缺陷的主流形态。

**Q15: SIR-4569 中，充电状态与充电枪连接状态由两个独立信号驱动时，为什么互相无条件覆写会冲掉对方的真实状态？应如何仲裁？**

A 信号变化时无条件写 B 的状态位，等于假设两个信号永远同步，而"充电结束≠拔枪"这类业务上的部分解耦必然产生错误覆盖；修复是把覆写改为带门禁的仲裁——写之前先查对方信号的缓存值再决定目标状态，并只允许低优先级方向回写。

某车机项目 SIR-4569 案例：OBC 充电状态变为 0x0 时旧代码无条件把 `Charging_Gun_State` 清 0，但此时充电枪实际仍连接（`acStatus=2`），Kanzi 收到 0 后直接关闭充电页面，"枪已连接"状态丢失；反向同样——枪状态变化时无条件写入 `Charging_State`，一次枪信号抖动会把"充电中(2)"降级为"枪已连接(1)"。修复（提交 `b577bfbe`）两处都改为有条件仲裁：

```diff
@@ updateChargingStateToKanzi()：充电结束（kanziState==0）先查枪状态缓存
+    boolean acConnected = mAcChargingGunStatus >= 2 && mAcChargingGunStatus <= 5;
+    boolean dcConnected = mDcChargingGunStatus == 2;
+    if (acConnected || dcConnected) {
+        kanziState = 1; // 枪仍连接：只降级为“枪已连接”，不再清零枪状态
+    } else {
+        kanziManager.setValue("", KanziType.CarModel.CHARGING_GUN_STATE, 0);
+    }
@@ updateChargingGunStateToKanzi()：加写入门禁
+    if (mObcChargeState == 0) { // 仅未充电时允许回写充电状态
+        kanziManager.setValue("", KanziType.CarModel.CHARGING_STATE, kanziState);
+    }
```

充电结束时枪仍连接则 `Charging_State` 置 1 且保留枪状态，充电页面停留在"枪已连接"；枪确实断开才清零。"充电中"的优先级高于"枪连接"，低优先级信号不再能覆写高优先级状态。

边界：仲裁基于进程内缓存，缓存陈旧时结论错，配套 `refreshChargingGunStateFromVehicle()` 从 VehicleService 重读真实枪状态兜底；仲裁条件分散在两个方法里，新增充电状态值要同时审视两处；复合状态"谁可覆写谁"的优先级要显式约定，不能靠代码顺序隐含。

**Q16: SIR-1662 中只在初始化代码里下发一次的渲染端配置字，若下发时 Kanzi 尚未连接会发生什么？这类一次性下发应如何设计时机？**

会静默丢失——下发代码以 `isKanziConnected` 为门，未连接时 `setValue` 被跳过且此后没有任何补发时机，渲染端按自身默认（高配）逻辑渲染；一次性下发的正确时机是"连接就绪"事件后补发，而不是只挂在初始化序列里。

某车机项目 SIR-1662 案例：中低配车模出现不该有的座椅调节入口，根因是初始化下发序列里根本没有配置字，Kanzi 拿不到配置等级就按默认高配渲染。修复（提交 `05ff7847`）在初始化序列补上 `sendConfigurationLevelToKanzi()`，把系统属性换算成 0/2 两档配置等级后主动下发，但下发仍以连接标志为门：

```java
private void sendConfigurationLevelToKanzi() {
    int seatPositionConfig = SysPropUtils.INSTANCE.getSeatPosition();
    int configurationLevel = seatPositionConfig == 0
            ? CONFIGURATION_LEVEL_HIGH
            : CONFIGURATION_LEVEL_LOW;
    // …（节选自真实 diff：LogUtils.d 日志一行略）
    if (isKanziConnected && kanziManager != null) { // 未连接时静默跳过，且无补发时机
        kanziManager.setValue("", KanziType.CarModel.CONFIGURATION_LEVEL, configurationLevel);
    }
}
```

Kanzi 据此在中低配隐藏座椅调节面板，功能开关闭环；但"只在初始化发一次"留下时序残余风险——Kanzi 连接晚于宿主初始化时配置丢失。

边界：Kanzi 经 aar/服务绑定连接，连接晚于应用初始化是常态而非异常，凡"进页面就必须正确"的属性（配置字、初始状态全量）都要有连接就绪后的补发或重推机制，与初始化推送构成两个触发时机；功能开关类属性要双向闭环，宿主下发路径与渲染端消费逻辑同版本交付，单侧上线即出缺陷。

**Q17: SIR-6343 的过滤已在输入、保存、读取、发送四个环节布防，为什么 Setting 侧入口仍能把 Emoji 送进 3D 车模？防过滤被旁路的收口方式是什么？**

因为过滤点选在"可选注入"的位置——`EditDialog.setInputFilter` 只约束自觉传入 filter 的调用方，Setting 侧直接 `new EditDialog` 的路径完全绕过；正确收口是把约束内置到专用入口类，所有调用方统一改用该子类，让过滤无法被绕开。

某车机项目 SIR-6343 的第二笔修复（Setting 侧，提交 `eeb6fcf2`）：Launcher 侧四环节过滤上线后，Setting 侧 `VehicleControlFragment` 仍直接构造 `EditDialog`，输入的 Emoji 照旧经保存、发送进入 Kanzi。修复把过滤器从调用方可选传入改为子类强制挂载：

```kotlin
// SeatRenameDialog.kt（节选）：继承 EditDialog；seatNameInputFilter 定义在其 companion object 内
override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    mBinding.etContent.filters += seatNameInputFilter // 约束内置子类，调用方无法绕过
    super.onViewCreated(view, savedInstanceState)
}

val seatNameInputFilter = InputFilter { source, start, end, _, _, _ ->
    val original = source.subSequence(start, end).toString()
    val filtered = sanitizeSeatName(original) // 按 code point 清洗，实现同 Launcher 侧（略）
    if (filtered == original) null else filtered
}
```

`RenameDialogManager` 与 `VehicleControlFragment` 两处入口统一改用 `SeatRenameDialog`，确认回调保留二次清洗，Emoji 从源头无法进入座椅名。

边界：双端各持一份 `sanitizeSeatName` 实现有漂移风险，理想是下沉公共组件单一实现；约束内置子类的前提是"所有入口都走该子类"，新增调用方仍可能直接用基类，评审要盯住入口清单；清洗规则的白名单区段需随 Unicode 演进补表。

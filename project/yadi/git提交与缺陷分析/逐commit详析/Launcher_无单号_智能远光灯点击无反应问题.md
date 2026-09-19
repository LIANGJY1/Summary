# 无单号 · 3D车模智能远光灯点击无反应
- **提交**：`2f2e1286` | 2026-07-15 | liqingqing | Launcher | bugfix
- **缺陷库**：未关联单号

## 问题
3D 车模上点击"智能远光灯"，界面无任何反应。

## 根因分析
Kanzi 上报 `Button.Light` 点击事件后，宿主的按钮分发 switch（`KanziSignalMapping`）里没有 `LIGHT` 分支，落入 `default` 仅打 warn；宿主也从未实现"L2A 下发 set value → ADAS 状态回调 → 回写 Kanzi"的闭环。即提交消息所说"没有给 L2A 接口发 set value 再回调给 kanzi"。同时 `VehicleService` 只注册了 `commStateCallback`，没有注册 `IVehicleStateCallback`，ADAS 状态（含智能远光灯 `adasInfo.param6`）根本进不来。属于"新增 Kanzi 交互按钮时宿主链路整体未实现"，而非局部笔误。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java（+40）、application/Launcher/src/main/java/com/yadea/launcher/manager/KanziType.java（+3）、application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java（+37）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ 按钮分发 switch 新增 LIGHT 分支
+            case KanziType.Button.LIGHT:
+                int currentState = (mVehicleService != null)
+                        ? mVehicleService.getCachedSmartLightState() : 0;
+                int nextState = (currentState == 1) ? 0 : 1;
+                int l2aValue = (nextState == 1) ? 1 /* SWITCH_ON */ : 0 /* SWITCH_OFF */;
+                // 1. 立即回传切换后的状态给 Kanzi
+                updateSmartLightStateToKanzi(nextState);
+                // 2. 通过 L2A 发送智能远光灯开关指令
+                if (mVehicleService != null) {
+                    mVehicleService.sendL2A(CarPropertyIds.IHC_SWITCH, l2aValue);
+                }
+                // 3. 延迟后取实际状态回传（removeCallbacks 防连点竞态）
+                ...
+                mHandler.postDelayed(mSmartLightFeedbackRunnable, SMART_LIGHT_FEEDBACK_DELAY_MS);
+                break;
--- application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java
+    private volatile int mCachedSmartLightState = 0;
+    private final IVehicleStateCallback vehicleStateCallback = new IVehicleStateCallback.Stub() {
+        @Override public void onVehicleAdasStatus(AdasInfo adasInfo) {
+            mCachedSmartLightState = adasInfo.param6;
+            KanziManager.getInstance(mAppContext).setValue("", "CarModel.SmartLight_State", adasInfo.param6);
+        }
+        ...
+    };
+    public void sendL2A(String id, int value) {
+        IviCommManager.getInstance().sendCommValue(id, "Set", value);
+    }
```
（连接/断开时对称注册/反注册 `registerVehicleStateCallback` / `unRegisterVehicleStateCallback`）

## 为什么能修复
打通三段链路：点击 → 立即回传乐观状态给 Kanzi（界面有反馈）→ `sendL2A(IHC_SWITCH)` 真正下发指令 → 1 秒后用 ADAS 回调缓存的 `mCachedSmartLightState` 校正回写；且 ADAS 回调本身也会实时推送 `CarModel.SmartLight_State`，双向修正。复用了此前 SIR-2178 沉淀的"乐观更新 + 具名 Runnable + removeCallbacks + 延迟回读"模式，一致性好。隐患：`getCachedSmartLightState` 初值 0，首击前若无任何 ADAS 回调，状态翻转可能与服务端真实值相反，需依赖 1 秒后校正。

## 复盘与经验
- **交互按钮是"一套协议"而非"一个 case"**：新增 Kanzi 按钮要同时落地枚举（KanziType.Button）、点击分支、下发、回传四处，漏一处即"无反应"，应做成 checklist。
- **状态回传双通道最稳**：主动延迟回读 + 被动状态回调同时存在，任一时序失败仍能收敛到真实值。
- **volatile 缓存跨线程读**：`mCachedSmartLightState` 由 Binder 线程写、主线程读，volatile 是最低要求，本次实现到位。

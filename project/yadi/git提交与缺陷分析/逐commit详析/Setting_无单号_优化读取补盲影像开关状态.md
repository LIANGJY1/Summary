# 无单号 · 优化读取补盲影像开关状态

- **提交**：`19386dc2` | 2026-09-15 | sgh | Setting | bugfix/优化
- **缺陷库**：未关联单号

## 问题
补盲影像开关（HUD 优先开关、转向补盲开关）的状态值与 HUD 侧显示不同步，进入设置界面时开关可能显示旧状态。

## 根因分析
`AssistedDrivingFragment` 在构建开关 UI 时只订阅了后续变化（点击回调里写 `SWITCH_ON/OFF`），进入界面那一刻并未主动回读车辆属性。当车端/HUD 侧在界面不可见期间改动了 `BLIND_SPOT_SWITCH` 或 `TURN_BSD_SW`，界面显示的就是陈旧缓存。修复方式是在两个开关初始化处各补一次 `settingVehicleService.getL2A(...)` 主动读取：`hasHudFeature` 分支读 `CarPropertyIds.BLIND_SPOT_SWITCH`，`hasFrontCam` 分支读 `CarPropertyIds.TURN_BSD_SW`。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
@@ //补盲影像预警优先显示在HUD
             if (hasHudFeature) {
+                settingVehicleService.getL2A(CarPropertyIds.BLIND_SPOT_SWITCH)
                 ssvBlindSpotHudPriority.switchCompat.setClickFastWithRebound(...) { ... }
@@ //转向补盲开关
             if (hasFrontCam) {
+                settingVehicleService.getL2A(CarPropertyIds.TURN_BSD_SW)
                 ssvTurnBlindSpotImage.switchCompat.setClickFastWithRebound(...) { ... }
```

## 为什么能修复
进入界面即主动 `getL2A` 拉取当前 CAN 属性值，回调刷新开关 UI，消除了"界面缓存 vs HUD 实际值"的失步窗口。与 022cd1a8（onResume 补 `getCanState()`）是同一套兜底思路。副作用小：每次进界面多两次属性读取；依赖 `getL2A` 回调线程正确切回主线程刷新。

## 复盘与经验
- 车机属性类开关必须"订阅变化 + 进场主动读"双通道，只靠回调会漏掉界面不可见期间的变化。
- 相同根因（进场不回读）在 Setting 内多处重复出现（本批次 022cd1a8/19386dc2），值得沉淀为基类 `BaseFragment` 的统一进场刷新机制。

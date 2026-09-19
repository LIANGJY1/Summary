# 无单号 · [SRS_VehSetting_006] pp0 新需求：可使用左侧滚轮调节 HUD 高度
- **提交**：`acd94ffc` | 2026-09-18 | sgh | Setting | feature
- **关联单**：无（SRS_VehSetting_006）

## 需求/目标
在 HUD 设置页停留时，允许用户用把手左侧滚轮直接调节 HUD 高度：按键事件（KEYCODE_CHANNEL_UP/DOWN）从 FW 侧下发，Setting 前台且位于 HUD 页时拦截并折算为高度档位指令；同时把"HUD 页面是否前台"实时同步给 FW，作为 FW 是否投递滚轮事件的依据。

## 实现结构
5 个文件、+189：
- **HudFwHelper.kt**（新增 82 行）：单例，经 `CarInputManager.setHudPageActive(active, fwToken)` 把 HUD 页面前台状态同步给 FW；含 `lastActive` 去重、服务未就绪时 `pendingActive` 挂起、异步取到 manager 后补发。
- **MainActivity.kt**（+28）：重写 `onKeyDown/onKeyUp`，`dispatchHudHeightKeyEvent()` 判定当前 Fragment 为 DisplayContainerFragment 且其内 HudFragment 处于 `isResumed`，则把事件交给 `hudFragment.onHudHeightKeyEvent()` 消费。
- **HudFragment.kt**（+77）：`onHudHeightKeyEvent` 将 UP/DOWN 折算为高度档 ±1，`coerceIn` 夹取上下限，`setCurrentGear` 更新 UI 并 `sendL2A(HUD_HEIGHT_ADJUST, target)` 下发，随后走页面既有的"超时回滚"协程对账；新增 `OnGlobalLayoutListener` + `onResume/onPause/onDestroyView` 多入口调用 `notifyHudPageActive()` 维护前台态（含 `hasHudAdjustment` 车型配置与 HUD 总开关校验）。
- **AndroidManifest.xml / whitelist/com.yadea.setting.xml**：申请 `android.car.permission.CAR_MONITOR_INPUT` 特权权限并加入 privapp 白名单。

数据流：FW 滚轮事件 → MainActivity 按键分发 → HudFragment 档位折算 → `sendL2A` 下发 HUD 高度 → 车端回报对账（超时回滚）。前台态：HudFragment 生命周期/布局变化 → HudFwHelper → FW。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
private fun dispatchHudHeightKeyEvent(keyCode: Int): Boolean {
    if (keyCode != KeyEvent.KEYCODE_CHANNEL_UP && keyCode != KeyEvent.KEYCODE_CHANNEL_DOWN) return false
    val container = mCurrentFragment as? DisplayContainerFragment ?: return false
    val hudFragment = container.childFragmentManager.fragments
        .filterIsInstance<HudFragment>()
        .firstOrNull { it.isResumed } ?: return false
    return hudFragment.onHudHeightKeyEvent(keyCode)
}
```

```kotlin
// application/Setting/src/main/java/com/yadea/setting/utils/HudFwHelper.kt
fun setHudPageActive(active: Boolean) {
    if (lastActive == active) return   // 去重，避免频繁 Binder 调用
    val inputManager = mCarInputManager
    if (inputManager != null) { callFw(inputManager, active); return }
    pendingActive = active
    requestCarInputManager()   // 服务未就绪：挂起意图，异步取到后补发
}
```

```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/HudFragment.kt
val target = (gearHeight.currentGear + changeHeight)
    .coerceIn(gearHeight.minGear, gearHeight.maxGear)
if (target == gearHeight.currentGear) return true   // 已到边界仍消费，防止事件外漏
gearHeight.setCurrentGear(target, true)
settingVehicleService.sendL2A(CarPropertyIds.HUD_HEIGHT_ADJUST, target)
```

实现讲解：三方协作分工清晰——FW 负责判断"该不该发"（依据 Setting 同步的前台态），Activity 负责按键路由（类型安全地从容器 Fragment 里找 `isResumed` 的 HudFragment），Fragment 只管业务（档位折算、下发、超时回滚复用页面既有基建）。`HudFwHelper` 的去重+挂起补发让高频生命周期变化不会刷爆 Binder。

## 复盘与要点
- **"前台态同步 + 按键拦截"双向握手**：硬件按键这类"全局输入"必须有明确的前台判定协议（谁有权消费）；本提交用 `setHudPageActive` + `isResumed` 双保险，且到边界档位仍返回 true 吞掉事件，避免滚轮把别的页面滚乱——事件消费边界的设计很完整。
- **特权权限要连白名单一起提**：`CAR_MONITOR_INPUT` 属 privapp 权限，Manifest 申请 + whitelist 声明 + android.car.jar 三件套缺一不可，是新接车机系统能力的标准清单。
- **遗留风险**：滚轮事件复用 `KEYCODE_CHANNEL_UP/DOWN`，若未来其他功能也要用这对键码，需要更细的仲裁层；`isHudPageForeground` 依赖 `OnGlobalLayoutListener` 高频回调做兜底刷新，虽有 `lastActive` 去重，仍需留意布局抖动下的额外计算。

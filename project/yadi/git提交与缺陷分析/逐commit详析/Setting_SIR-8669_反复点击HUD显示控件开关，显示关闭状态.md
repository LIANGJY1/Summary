# SIR-8669 · 反复点击 HUD 开关，显示关闭却仍显示 HUD 设置项
- **提交**：`b398623b` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 待测试验证 · 域 车控车设

## 问题
反复点击 HUD 显示控件开关后，开关停在关闭状态，但下方的 HUD 设置项功能区域仍然显示，开关与内容区状态脱节。

## 根因分析
`HudFragment` 的 HUD 开关走 `SwitchHelper.setupSwitchWithRebound` 回弹机制：点击时视觉先翻到目标态并下发 `HUD_SWITCH` 命令（`onChanged`），若 1 秒内车控信号未确认，协程 `delay(1000L)` 到期把 `switch.isChecked = previousState` 回弹回真实状态并调用 `onReboundCallback(targetState)`。原代码只传了 `onChanged`，没有接 `onReboundCallback`——回弹发生时开关视觉回到"关"，但控制底部内容区显隐的 `updateHudSwitchUI` 没有被调用，内容区停留在开启时的展示状态，出现"开关关着、内容还在"的脱节。缺陷库 rc"频繁切换按钮回弹未刷新可见区域"即此。

## 关键代码修改
改动文件：HudFragment.kt（+5）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/HudFragment.kt
                 settingVehicleService.sendL2A(CarPropertyIds.HUD_SWITCH, value)
                 logClick("Send HUD switch setting command: $value")
                 hudSwitchStateTemp = isChecked
+            },
+            onReboundCallback = { targetState ->
+                // 避免开关与底部内容区状态脱节（开关关着但内容区仍显示）
+                logObserve("HUD switch rebound, restore UI to ${!targetState}")
+                updateHudSwitchUI(!targetState)
             })
```

## 为什么能修复
把回弹回调接上后，1 秒超时回弹（开关翻回 `previousState`）的同时以 `!targetState`（回弹后的真实状态）刷新内容区显隐，`updateHudSwitchUI` 会同步隐藏 HUD 设置项，开关与内容区重新一致。车控确认及时到达的场景不触发回弹，行为不变。副作用：回弹刷新与信号观察者刷新可能重复执行一次 `updateHudSwitchUI`，幂等无影响。该模式与 `SwitchHelper.jobs` 的 WeakHashMap 协程取消机制配合：确认到达时 job 被 cancel，`onReboundCallback` 不会误触发。

## 复盘与经验
- "乐观更新 + 超时回弹"的开关必须把回弹视为一次完整的状态变更事件：`onReboundCallback` 里要恢复的不止开关本身，还有所有联动的可见区域。
- 公共控件封装（SwitchHelper）回调参数给默认值（`onReboundCallback = {}`）虽然降低接入成本，但调用方漏接回调时错误被静默吞掉——新开关联动内容区时应把"回弹联动"列为接入清单项。
- 反复快速点击是触发回弹路径的最短用例，凡有回弹机制的开关都应覆盖"点击后不等待信号、立即再点击/等待超时"两条分支。

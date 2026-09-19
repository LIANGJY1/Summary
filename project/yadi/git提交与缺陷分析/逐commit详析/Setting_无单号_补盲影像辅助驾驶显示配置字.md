# 无单号 [SRS_VehSetting_066] 补盲影像辅助驾驶显示配置字
- **提交**：`e668e23c` | 2026-08-25 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_066

## 需求/目标
按车型配置字（系统属性）裁剪辅助驾驶页与显示页：无 HUD 硬件时隐藏 HUD 相关开关及 HUD Tab；无前摄像头时隐藏"转向补盲影像"开关，实现一套代码适配多配置车型。

## 实现结构
- `SysPropUtils.kt`：已有 `getHudConfig()`、`getCamFront()`（persist.verdor.yadea.cfg.*），补注释说明 0000=无前摄像头。
- `AssistedDrivingFragment.kt`：新增 `hasHudFeature`/`hasFrontCam` 两个配置常量；`initSwitchState` 按配置 setVisible 控制开关与"补盲影像"分组（dividerBlindSpot/tvBlindSpot 两者都无时整组隐藏）；`setListener` 中三个开关的 listener 注册包进 `if (hasXxx)`；P 挡副标题/置灰/回显函数头部加 `if (!hasHudFeature) return` 守卫；删除 initSwitchState 里 40 行遗留的测试注释块。
- `DisplayContainerFragment.kt`：Tab 标题与 Fragment 列表改为动态构建，`getHudConfig()==1` 才追加 HUD Tab。
- `fragment_assisted_driving.xml`：补盲影像分组增加 divider/tv id。
- 数据流：系统属性（车型配置）→ Fragment 初始化时一次性读取 → 控制 UI 存在性与 listener 注册 → 无配置车型不会发出 HUD/前摄相关信号。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/AssistedDrivingFragment.kt
+            if (!hasHudFeature) {
+                ssvAssistHudPriority.setVisible(false)
+                ssvBlindSpotHudPriority.setVisible(false)
+            }
+            if (!hasFrontCam) {
+                ssvTurnBlindSpotImage.setVisible(false)
+            }
+            if(!hasHudFeature&&!hasFrontCam){
+                dividerBlindSpot.setVisible(false)
+               tvBlindSpot.setVisible(false)
+            }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayContainerFragment.kt
+    override fun getTabTitles(): List<String> {
+        val titles = mutableListOf(
+            getString(R.string.tab_display_screen),
+            getString(R.string.tab_dashboard)
+        )
+        if (SysPropUtils.getHudConfig() == 1) {
+            titles.add(getString(R.string.tab_hud))
+        }
+        return titles
+    }
```
裁剪做在了三个层面：UI 可见性、listener 注册、信号发送函数守卫——即使后续有人误注册 listener，发送函数头的 `if (!hasHudFeature) return` 也能兜住。配置读取用 `val` 一次性缓存（配置字运行期不变），避免每次判断都读系统属性。

## 复盘与要点
- 配置字裁剪要覆盖"入口可见、事件注册、信号发送"三层，只隐藏 UI 不拦 listener 是配置适配最常见的漏点。
- Tab 列表动态化是低成本的多车型适配（对比为每个车型出独立包），代价是 title 与 fragment 两个列表必须严格同序同步追加。
- 遗留风险：`hasHudFeature`/`hasFrontCam` 在 initSwitchState 与 setListener 分散判断，新增 HUD 相关控件时容易漏加条件，可考虑集中一个 `applyFeatureConfig()`。

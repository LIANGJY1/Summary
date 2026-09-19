# SIR-2174 · 低配车型（无HUD配置）控制中心仍显示HUD亮度调节
- **提交**：`acc6b97c` | 2026-07-15 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 系统需求

## 问题
用 2E 服务写入低配车型配置字（无 HUD）后，下拉控制中心仍然显示 HUD 亮度调节控件。

## 根因分析
`QuickSettingFragment` 原实现无条件膨胀同一布局 `fragment_quick_setting`（内含 `in_quick_setting`，带 `hudBrightnessLayout`），并在 `WHAT_INIT_DATA` 消息里无条件创建 `HUDBrightnessTile`。整个链路没有任何"是否配置了 HUD"的判断，配置字（2E 服务写入的系统属性）被完全无视，低配车自然露出 HUD 控件。与缺陷库"未添加对应判断逻辑"一致——这是配置字驱动 UI 的完整缺失，而不是局部判断漏写。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt（+13/-6）、res/layout/fragment_quick_setting.xml（+190/-2，展开 include）、res/layout/fragment_quick_setting_no_hud.xml（+135，新增）、res/layout/in_quick_setting.xml（-194，删除）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/QuickSettingFragment.kt
@@ 配置读取
+    private val mHasHudAdjustment = SysPropUtils.getHudConfig() == 1
@@ Tile 创建加条件
-                    hudBrightnessTile = HUDBrightnessTile(
-                        viewLifecycleOwner, hudBrightnessLayout, settingService)
+                    if (mHasHudAdjustment) {
+                        hudBrightnessTile = HUDBrightnessTile(
+                            viewLifecycleOwner, hudBrightnessLayout!!, settingService)
+                    }
@@ 按配置选布局
-        mView = inflater.inflate(R.layout.fragment_quick_setting, container, false)
+        mView = inflater.inflate(if (mHasHudAdjustment) R.layout.fragment_quick_setting
+                                 else R.layout.fragment_quick_setting_no_hud, container, false)
```
（`hudBrightnessLayout` 相应从 `lateinit var` 改为可空 `var ... ?`；新增 no_hud 布局为去掉 HUD 区块的版本）

## 为什么能修复
在布局膨胀与 Tile 创建两个层面都以 `SysPropUtils.getHudConfig() == 1` 为开关：低配走无 HUD 的独立布局、不创建 `HUDBrightnessTile`，控件从视图树层面消失，而非靠 visibility 隐藏。风险点：配置在 Fragment 构造时一次性读取（`mHasHudAdjustment` 是 val），运行中用 2E 服务改配置字不会实时生效，需重启 SystemUI/重新进入界面；`hudBrightnessLayout!!` 依赖有配置布局中该 id 必存在，布局双份维护有漂移风险。

## 复盘与经验
- **配置字驱动 UI 要在"布局选择"层落地**：结构差异大时用双布局（或 ViewStub），比在代码里逐个 hide 更不易漏。
- **配置读取时机决定生效时机**：构造期读一次的快照式配置，遇"运行中改配置字"的测试路径（2E 服务写死）必然不同步，生命周期内要么监听变更要么注明需重启。
- **同一功能双布局必须同步维护**：no_hud 布局复制自原布局，后续控件增删要改两处，长期宜用 include+条件 include 收敛。

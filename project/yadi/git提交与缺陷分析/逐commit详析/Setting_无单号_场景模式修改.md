# 无单号 [SRS_VehSetting_063] 场景模式修改
- **提交**：`3998641e` | 2026-08-12 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_063

## 需求/目标
完善场景模式页（SceneModeFragment）：迎宾模式三个子开关（座椅/灯光/音效）增加互锁置灰逻辑（迎宾开启时三者不能同时全关，最后一个开启项禁止关闭）；迎宾档位关闭时隐藏三个子开关；驻车断电提示改用 SentinelDialog 弹窗展示。

## 实现结构
- `ui/fragment/SceneModeFragment.kt`（主体）：新增 `checkAndDisableSeatIfNeed/LightIfNeed/SoundIfNeed` 三个互锁检查函数，分别在开关回显 `updateWelcomeXxxUI` 中调用；`updateWelcomeModeGearUI` 按档位控制子开关可见性；脚撑下放时间 seekbar max 30→300；驻车断电 tips 弹窗接入。
- `diologfragment/SentinelDialog.kt`：从无参硬编码弹窗改造为带 `title/content` 构造参数的通用提示弹窗，空标题自动隐藏。
- 顺带清理：删掉低电量/湿滑模式的 pending 态（移交后续提交）、修复 seekbar 文字位置计算 `seekBar.left` 偏移。
- 数据流：互锁判断直接读 `settingVehicleService.getAnyProperty` 与其他开关 LiveData 的当前值，而非维护本地镜像状态。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
+    private fun checkAndDisableSeatIfNeed() {
+        val gear =
+            settingVehicleService.getAnyProperty(CarPropertyIds.WELCOME_MODE_GEAR) as? Int ?: 0
+        val lightOn =
+            (settingVehicleService.getAnyProperty(CarPropertyIds.WELCOME_LIGHT_SWITCH) as? Int
+                ?: 0) == 0
+        val soundOn = (settingVehicleService.welcomeSoundEffectSwitch.value ?: 0) == 0
+        val shouldDisable = gear != 0 && lightOn && soundOn
+        mBinding.swWelcomeSeat.let { switchCard ->
+            updateSwitchDisabledState(switchCard, shouldDisable)
+        }
+    }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/SentinelDialog.kt
-class SentinelDialog() : BaseDialogFragment() {
+class SentinelDialog(
+    private val title: String = "",
+    private val content: String = ""
+) : BaseDialogFragment() {
+    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
+        ...
+        if (title.isNotEmpty()) { tvTitle.visibility = View.VISIBLE; tvTitle.text = title }
+        else { tvTitle.visibility = View.GONE }
+        if (content.isNotEmpty()) { tvContent.text = content }
+    }
```
互锁语义："置灰判定 = 迎宾档开启 && 另外两个子开关都为关"。注意其中 `(x as? Int ?: 0) == 0` 把"取不到值/0"当作关闭态参与判断，代码注释写的是 lightOn，实际比较的是"值==0"，语义上变量命名与判断相反——这是典型的快速迭代痕迹，靠 UI 回显路径反复触发检查来兜底。置灰复用 `SkinSwitchCardView` 的 alpha + overlay 组合（alpha 0.3 + enableOverlay），与 DrivingSafetyFragment 保持一致。

## 复盘与要点
- 三开关"至少保留一个"互锁是常见需求，实现上选择"回显时做检查 + overlay 拦截点击"而不是集中式状态机，复用性好但三个函数高度相似，可抽象为一个通用函数。
- SentinelDialog 参数化改造让一个页面专用弹窗变成通用组件，是低成本高收益的组件复用手法。
- 遗留风险：互锁读取 `getAnyProperty` 与 LiveData 混用，初始化时序上可能拿到旧值；后续提交（027da812 等）继续在该页迭代说明逻辑仍在演化。

# 无单号 · 527分支的黑夜模式同步到主分支（EnergyManagement 夜间模式补齐）

- **提交**：`3ba0d479` | 2026-06-27 | liqingqing | EnergyManagement | 类型：分支同步提交（[bugfix] 标签、影响等级 C，实为把 527 分支上已开发的黑夜模式功能合回主分支）
- **缺陷库**：未关联单号

## 问题
主分支的 EnergyManagement（能耗管理）模块没有黑夜模式适配：夜间模式下电量条、进度文字、弹窗背景仍用日间配色（如写死的 `#1F222A`、`0xFF404C60`），且电量条切档动画帧 `energy_block_red_to_yellow_*` 等只有日间版资源，夜间刺眼、显示不符。

## 根因分析
典型的"分支开发、漏合主线"：黑夜模式在 527 分支完成但未及时同步主分支。技术上主分支代码存在三类不适配点：其一，自定义 View `EnergyBarSeekBar` 用 `getResources().getIdentifier("energybattery_" + soc, ...)` 按电量值动态拼资源名，天然不会命中 `_night` 后缀的夜间资源；其二，`MainActivity` 直接 `setTextColor(0xFF404C60)` 写死 ARGB；其三，`textPaint.setColor(Color.parseColor("#1F222A"))` 在 View 构造时写死画笔颜色。本提交共 213 个文件（+368/-140），批量带入夜间 PNG 帧资源（`*_night.png` 二进制）、`values-night/colors.xml`、主题与样式调整，并给自定义 View 增加夜间分支与底部光晕动画能力。

## 关键代码修改
改动文件：application/EnergyManagement 下 AndroidManifest.xml、view/custom/{EnergyBarSeekBar,EnergyDistributionView,LabelValueLayout,ReservationChargingDialog,TimePickerView,WheelScrollView}、view/ui/MainActivity.java、100+ 张 `*_night.png`（二进制）、res/values-night/colors.xml（新增）、多个 drawable/layout/style/themes 资源

```diff
--- application/EnergyManagement/.../view/custom/EnergyBarSeekBar.java
@@ 夜间模式判定 + 动态资源名带 _night 后缀
+    private boolean isNightMode() {
+        int nightMode = getResources().getConfiguration().uiMode
+                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
+        return nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES;
+    }
@@ setBatterySoc
-        int drawableResId = getResources().getIdentifier(
-                "energybattery_" + clampedSoc, "drawable", getContext().getPackageName());
+        String resourceName = "energybattery_" + clampedSoc
+                + (isNightMode() ? "_night" : "");
+        int drawableResId = getResources().getIdentifier(resourceName, ...);
@@ 构造：写死颜色 → 颜色令牌 + 字体
-        textPaint.setColor(Color.parseColor("#1F222A"));
+        textPaint.setColor(ContextCompat.getColor(context, R.color.text_default_default));
+        textPaint.setTypeface(EnergyTypeface.semibold(context));
```

```diff
--- application/EnergyManagement/.../view/ui/MainActivity.java
@@ 电量条切档动画增加夜间光晕帧序列
-            binding.energyBarSeekBar.playBatteryTransition("energy_block_red_to_yellow", 90, 100, currentSoc);
+            binding.energyBarSeekBar.playBatteryTransition(
+                    "energy_block_red_to_yellow",
+                    "night_energy_bar_bottom_glow_red_to_yellow",
+                    90, 100, currentSoc);
@@ 删除写死的文字颜色
-            binding.tvSlowChargeThumbValue.setTextColor(0xFF404C60);
+//          （改由主题/drawable 状态色接管）
```

## 为什么能修复
`isNightMode()` 把 `uiMode` 的 `UI_MODE_NIGHT_MASK` 判定注入动态资源名拼接，使 `getIdentifier` 能命中夜间帧资源——这是本类资源加载方式的唯一适配点；写死颜色全部换成 `values-night` 可覆盖的颜色令牌后，夜间配色由资源系统接管。附带增强了电量条：新增 `setBottomGlowImageView` + `OnBatteryTransitionFinishedListener`，切档时同步播放底部光晕帧并回调最终 SOC。隐患：夜间资源靠 `getIdentifier` 字符串拼接，资源缺失时静默回退日间图（回退逻辑存在，属合理防御），但重命名夜间资源不会有编译期报错。

## 复盘与经验
- **`getIdentifier` 动态取资源必须考虑限定符**：按名字拼资源会绕过 `-night`/`-hdpi` 等资源限定符机制，凡走这条路都要手动拼后缀并做缺失回退。
- **颜色一律走令牌**：`Color.parseColor`/`0xFF404C60` 写死一处，主题化时就是一处漏网；本提交逐处替换的成本远高于最初就用 `@color`。
- **分支同步要有清单**："527 分支未来得及同步"式的 213 文件大提交风险高，功能分支应尽早小步合回主干，而不是事后一次性搬运。

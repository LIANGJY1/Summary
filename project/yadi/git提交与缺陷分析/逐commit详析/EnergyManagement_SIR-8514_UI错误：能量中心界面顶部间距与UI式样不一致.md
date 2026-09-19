# SIR-8514 · 能量中心界面顶部间距与 UI 式样不一致
- **提交**：`a3258695` | 2026-09-17 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心界面整体内容顶部间距与 UI 式样不符——页面从状态栏顶端（y=0）开始绘制，顶部留白/内容位置整体偏上。

## 根因分析
`EnergyManagement` 的 `BaseActivity.setDecView()` 在设置 SystemUI 可见性时，把 `View.SYSTEM_UI_FLAG_LAYOUT_STABLE or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` 一起设到 decorView 上。`SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 会让窗口内容布局延伸到状态栏区域，即从 y=0 开始排布，而该应用式样是"内容位于系统栏之内"的常规布局，未配合 `fitsSystemWindows` 消费 insets，于是顶部间距比设计稿少了一个状态栏高度。缺陷库 rc 字段完整记录了这一机制（"使窗口从y=0（状态栏顶部）开始绘制"）。

## 关键代码修改
改动文件：BaseActivity.kt（单处，-5/+1）
```diff
// application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/base/BaseActivity.kt
-        val option = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE    //NOSONAR
-                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN    //NOSONAR
-                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)    //NOSONAR
-
         val vis = window.decorView.getSystemUiVisibility()    //NOSONAR
-        window.decorView.setSystemUiVisibility(option or vis)  //NOSONAR
+        window.decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE or vis)  //NOSONAR
         window.statusBarColor = Color.TRANSPARENT
```

## 为什么能修复
去掉 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 与 `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION` 后，窗口内容不再延伸进状态栏/导航栏区域，恢复默认的系统栏 inset 行为，页面从系统栏下方开始布局，顶部间距回到式样标注位置；仅保留 `SYSTEM_UI_FLAG_LAYOUT_STABLE`，保证系统栏显隐时布局不跳动。副作用：若某些子页面曾按"全屏延伸"设计并自行消费 insets，会受此基类改动影响——本应用无此类页面，故安全。该修复也印证了缺陷库 sol 的完整表述。

## 复盘与经验
- `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` 是"布局"flag 不是"绘制/显示"flag：它改变内容排布起点，与 `FLAG_FULLSCREEN`（隐藏状态栏）完全是两回事，混用是顶部间距类 bug 的高发原因。
- 基类里统一设置 SystemUI flags 会波及全部子页面，添加前应确认所有页面的式样是否都按全屏延伸设计；车机多屏多式样场景尤其要谨慎。
- "界面偏移/间距不对"类 UI bug 的排查顺序：先查 decorView systemUiVisibility 与窗口 flags，再查 fitsSystemWindows/insets 消费，最后才是布局参数本身。

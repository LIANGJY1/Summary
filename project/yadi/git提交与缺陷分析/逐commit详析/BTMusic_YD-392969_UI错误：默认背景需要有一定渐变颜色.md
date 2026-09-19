# YD-392969 · BTMusic 默认背景缺少渐变色与 UI 不一致
- **提交**：`ab5845d8` | 2026-07-30 | hedeyuan | BTMusic | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
蓝牙音乐未连接（忘记/默认）页面的背景图缺少 UI 稿要求的渐变颜色，显示与设计不符。

## 根因分析
未连接视图 `cl_forget_content` 的背景引用 `R.drawable.bg_main_forget`（布局 `activity_main.xml` 中 `android:background="@drawable/bg_main_forget"`），主题切换逻辑 `MainActivity.switchTheme()` 中也用代码再次设置 `it.clForgetContent.background = resources.getDrawable(R.drawable.bg_main_forget, null)`。旧背景资源无渐变，与 UI 稿不符；属于纯资源替换问题，无逻辑缺陷。注意代码与布局两处引用同一背景，换图必须同步改两处。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`、`application/BTMusic/src/main/res/layout/activity_main.xml`、新增 `drawable-mdpi/background_nomal.png` 与 `drawable-night-mdpi/background_nomal.png`（二进制图片资源，昼/夜两套）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt (switchTheme)
-            it.clForgetContent.background = resources.getDrawable(R.drawable.bg_main_forget, null)
+            it.clForgetContent.background = resources.getDrawable(R.drawable.background_nomal, null)
--- application/BTMusic/src/main/res/layout/activity_main.xml
                 android:id="@+id/cl_forget_content"
-                android:background="@drawable/bg_main_forget"
+                android:background="@drawable/background_nomal"
```

## 为什么能修复
新增带渐变的 `background_nomal` 资源，并利用资源限定符同时提供 `drawable-mdpi`（白天）与 `drawable-night-mdpi`（黑夜）两套图，系统夜间模式切换时自动加载对应版本；布局与 `switchTheme()` 两处引用同步替换后，未连接页背景即带渐变且昼夜自适应。隐患：`bg_main_forget` 资源未删，命名 `nomal` 为 `normal` 拼写错误，后续易产生混淆。

## 复盘与经验
- 同一背景常常"布局 XML + 代码 switchTheme"双路引用，换资源时必须两处同步，否则主题切换瞬间/特定路径下出现旧图。
- 需要昼夜差异的图片用 `-night` 资源限定符是最省代码的方案，避免在代码里手写 if(night) 分支。
- 资源命名拼写（background_nomal）在合入前应纠正，错拼资源一旦被广泛引用便难以再改名。

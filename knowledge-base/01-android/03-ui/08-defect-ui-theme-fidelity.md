# UI 还原与主题适配：资源完整性与设计稿落地的可迁移规则

> 学习资料（文章模式沉淀）。机制按 AAOS13（Android 13）本地源码核对并逐题标注，不在本地树的组件按源材料（Android 17 锚点）转写并标注版本差异。主线：该项目 761 条缺陷修复中 UI 还原与主题适配占 264 条（34.7%），本文把这一大类提炼为可迁移规则——硬编码色值的三处盲区、night 资源完整性、动态资源名与同名资源遮蔽两大陷阱、字体字重覆盖范围、弹窗与窗口层级的视觉边界，以及设计稿版本治理和"日夜 × 分辨率"提测矩阵。2026-09-26 修订：补代码级讲解与深案例覆盖。Q 序列即结构，供 atlas 同源直读。2026-10-04 复核：更正 getIdentifier 与资源限定符、非整百字重的旧结论；Q11/Q18 按 Android 官方 API 行为校正。2026-09-26 二次修订：消除跨题引用改为题内自足；段落并列项拆为列表；新增 Q25（SRS 变更双向核对：删除侧引用检索与多语言目录同步、新增侧按条目逐项确认）；新增 Q16（ConstraintLayout 尺寸语义：match_parent 绕过约束链、margin 与 padding 语义差异）。

**Q1: [learning] 深浅色双主题的车机应用里，UI 还原与主题适配类缺陷为什么能占到修复总量的三分之一？根因集中在哪几类形态？**

因为"颜色可以写死、资源可以漏配、设计稿会变"三个自由度叠加，任何一处没有走资源系统或没有配齐限定符，都会在另一种主题下变成视觉缺陷，而这类问题常规功能测试发现不了。该项目 761 条修复中此类占 264 条，根因集中在六类形态：

1. **硬编码色值**：布局 textColor、style、drawable 的 solid 三处盲区写死十六进制值（SIR-6618、SIR-7246、SIR-6620）；
2. **night 资源缺失**：新增颜色或图片只配日间值，甚至配套提交漏提 values-night 文件（SIR-6224）；
3. **动态资源名与限定符资源名不一致**：`getIdentifier` 只能按传入的逻辑资源名查 ID；如果日夜图片被命名成两个不同条目（例如 `_night` 后缀），调用侧必须自行选择，而同名条目的 `drawable-night` 变体仍由资源系统按配置选择（3ba0d479）；
4. **同名资源遮蔽**：app 壳工程资源覆盖公共库资源，库组件交互态失效（SIR-7514）；
5. **字体能力限制**：非整百字重请求未必能由所用字体提供，静态字体可能只能匹配到邻近字重；
6. **设计稿版本混淆**：需求变更后布局与代码联动未成对清理（SIR-4333）。

对应的防回归清单有四条：

1. 新增颜色时补齐 night 值。
2. 样式颜色统一引用语义 token。
3. 可静态枚举的动态资源名改用 `R` 常量。
4. UI 提测覆盖"日夜 × 分辨率"矩阵。

**Q2: [learning] 布局里把 textColor 写成 #000000 这类硬编码色值，为什么浅色模式测试通过仍会在深色模式翻车？正确写法是什么？**

硬编码色值不参与资源限定符匹配，系统切到夜间模式时资源查找拿不到替代色，控件保持写死的颜色——浅色背景下正确的颜色在深色背景上立刻不可见。SIR-6618 中登录页协议行的标点 TextView 写死 `#000000`，浅色模式下与黑色正文视觉一致没有暴露，深色模式下背景变黑后标点几乎不可见；修复是替换为语义色 `text_default_default`，颜色随主题自动切换。修复 diff 只有两处属性替换（节选自真实 diff）：

```diff
--- application/AccountCenter/src/main/res/layout/activity_login.xml
                 android:text="@string/comma"
-                android:textColor="#000000"
+                android:textColor="@color/text_default_default"
                 android:textSize="24sp"/>
```

`android:text` 提供显示文本，本例引用逗号字符串；`android:textColor` 指定文字颜色，本例用语义资源以参与日夜限定符选择；`android:textSize="24sp"` 指定按字体缩放的 24sp 字号，原值保留，因此颜色修复不改变字号。省略 `textColor` 会继承 TextView 样式/主题颜色而非自动选择此语义 token。`text_default_default` 在 values 与 values-night 下分别映射浅/深色值，标点颜色随主题自动切换；浅色模式取值不变。

硬编码色值有三处盲区，静态检查可全覆盖：布局属性的 `textColor`/`background`、style 里的颜色项、drawable（shape/selector）内的 `solid android:color`。判断规则：深浅色双主题项目里文本颜色一律用语义色资源，布局中出现 `#000000`/`#FFFFFF` 就是深色模式的定时炸弹；同理，"首次进入才复现"的细节提示深色验证要覆盖冷启动首屏，不能只测动态切换路径。

**Q3: [learning] drawable 内写死深色半透明导致"白天有、黑夜无"的视觉元素，排查入口是什么？（SIR-7246）**

排查入口是对比度，不是控件逻辑。SIR-7246 中协议弹窗的滚动条 thumb 写死 `#3320232B`（20% 透明度的深色）：白天浅色背景下深色 thumb 可见；黑夜模式弹窗背景为深色，深色半透明 thumb 与背景对比度趋近于零，视觉上等于滚动条消失。修复只有一行——solid 色换成主题化 token `text_default_disabled`，其日夜两套取值都与背景保持足够对比度（节选自真实 diff）：

```diff
--- application/Setting/src/main/res/drawable/scrollbar_thumb.xml
-    <solid android:color="#3320232B"/>
+    <solid android:color="@color/text_default_disabled"/>
```

判断规则：凡是"白天有、黑夜无"的视觉元素，先把这个元素的颜色和它所在背景的颜色都查一遍算对比度，而不是先怀疑滚动、绘制等控件逻辑。附带治理项：同一组件的样式应全仓库统一为一份 drawable 加 token，避免出现两套口径各配一份。

**Q4: [learning] 深色模式下弹窗整体仍是浅色配色，典型根因与修复路径是什么？（SIR-6620）**

根因是弹窗的所有颜色都是日间值：布局里的标题、说明、失败提示 `textColor` 硬编码浅色十六进制，背景 drawable 的 solid 也硬编码浅色——硬编码不参与 `values-night` 匹配，夜间自然维持浅色。修复路径分两步：

1. 把硬编码色抽取到 `values/colors.xml`，并让布局与 drawable 统一引用；日间默认值保持与原色一致，以免引入白天回归。
2. 在 `values-night/colors.xml` 定义同名夜间值，让资源系统按当前配置选择颜色。

两个流程教训来自该案例本身：一是颜色资源名带业务前缀（如 `qr_dialog_`），避免全局 colors.xml 命名冲突；二是这次修复确实发生过夜间资源文件漏提——第一笔提交完成资源化并引用 `@color/qr_dialog_*`，配套的 `values-night/colors.xml` 却漏于提交，由补丁提交单独补上（节选自真实 diff）：

```diff
--- /dev/null
+++ application/AccountCenter/src/main/res/values-night/colors.xml
+<resources>
+    <color name="qr_dialog_background">#222325</color>
+    <color name="qr_dialog_title_text">#EEEEEE</color>
+    <color name="qr_dialog_secondary_text">#99EEEEEE</color>
+</resources>
```

这三条颜色定义分别提供二维码弹窗背景、标题文字、次级文字的夜间颜色，十六进制值是对应颜色的 ARGB/RGB 字面量；`values-night` 目录只在夜间资源配置下参与选择，省略同名 night 条目时系统回退到 `values` 默认颜色，不会因缺少 night 值而编译失败。引用了 night 文件却没提交它时，diff stat 可发现引用与新增不成对。拆 commit 前要本地编译并日夜双模式各跑一遍。

**Q5: [learning] "新增颜色必配 night 值"这条清单项依据的失败模式是什么？如何用流程兜底？**

依据的失败模式是 Android 资源查找的回退规则：引用一个颜色资源时，若 `values-night` 下没有同名定义，系统静默回退到日间默认值，编译期不报任何错——写代码的人完全无感知，缺陷只在夜间运行时暴露。这条规则同时意味着"抽了资源但没配 night 值"和"根本没抽资源"在夜间效果一样，适配工作必须以"夜间值存在"为完成标准，而不是"颜色资源化"。

流程兜底分为三项：

1. 涉及颜色的新增或修改，提交前编译并在日夜模式各检查首屏。
2. 把“新增颜色必配 night 值”写进评审清单；每个 `values/colors.xml` 新条目都要核对 night 目录。
3. 评审时扫描同页布局的 `textColor`/`background`、style 颜色项和 drawable 的 `solid android:color`，清除未资源化的硬编码色。

**Q6: [learning] 夜间模式下无封面兜底图显示的仍是白天素材，最省事的修复方式是什么？（SIR-6224）**

最省事的方式是资源限定符：在 `drawable-night`（含密度后缀，如 `drawable-night-mdpi`）目录下放同名的夜间版图片，资源系统在夜间 UI 模式下自动命中，代码零改动。SIR-6224 中媒体卡片无专辑封面时统一引用 `default_cover`，工程只有白天版资源，夜间查找回落到白天图；整笔修复就是新增一个文件，引用侧一行未动：

```diff
+application/BTMusic/src/main/res/drawable-night-mdpi/default_cover.png  (Bin, 120608 bytes)
```

边界与验证两点：只补单一密度变体时，高密度设备匹配不到精确密度目录会回落到该图，小图放大可能模糊，条件允许按设计规格补全密度或改用矢量图；验收媒体类界面要专门构造"无封面曲目"场景，默认兜底图是这类应用的高频视觉缺陷点。判断规则：双主题界面的每张运营图/默认图都要问一句"黑夜版在哪"，缺资源比错代码更常见。

**Q7: [learning] 用 getIdentifier 按"前缀 + 数值"拼资源名取图，为什么夜间帧图可能取错？怎样修复并控制风险？（3ba0d479）**

`getIdentifier` 按传入的逻辑资源名返回资源 ID，本身不会绕过 Android 的配置限定符选择。同一逻辑名在 `drawable/` 与 `drawable-night/` 下各有一份时，ID 相同，随后读取 drawable 时仍会依当前资源配置选中夜间版本；3ba0d479 的问题是夜间帧图采用了 `_night` 后缀、成为另一组逻辑名，而调用只拼出 `energybattery_` 加电量值，因此查不到那组夜间名字。

修复只能针对这类“日夜资源名不同”的组织方式：先读取当前 `uiMode` 的夜间位，再在名字上追加 `_night`，并检查返回 ID 是否为 0，未命中时按产品规则回退。更稳妥的设计是尽量让日夜图使用相同逻辑名并放入 `drawable-night`；如果帧图逻辑必须用不同名称，就把动态查找集中封装并覆盖夜间、白天、缺资源三种情况。`getIdentifier` 仍有编译期不可检查、按字符串查找效率较低的代价；资源名可静态枚举时优先使用 `R.drawable`（节选自真实 diff）：

```diff
// EnergyBarSeekBar.java：夜间判定注入动态拼名
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
+        int drawableResId = getResources().getIdentifier(resourceName, "drawable", getContext().getPackageName());  // …（原调用有真实省略，节选自真实 diff）
```

调用参数中，`resourceName` 是逻辑条目名，`"drawable"` 限制资源类型，包名指定查找哪个应用包；缺省包名时可传 `null`，本例显式传当前 app 包名。返回 0 表示没找到。风险在维护侧：这条路径上的资源重命名不会有编译期报错，夜间资源名缺失时会查不到而需走明确的回退逻辑。动态资源名能改静态引用（`R.drawable.xxx` 加多份同名限定符资源）就改；必须动态时，把拼名逻辑集中封装并对日夜两态各测一遍。

**Q8: [learning] 多模块工程中，app 壳工程定义与公共库同名的 drawable，为什么会"劫持"库组件的交互态？（SIR-7514）**

资源合并规则是同名的资源 app 模块覆盖库模块——壳工程的资源优先级最高。SIR-7514 中公共库定义了标准按下选择器 `selector_common_white_btn`，其按下态引用 `shape_btn_white_selected`（带明显按压色）；而壳工程自己也定义了一个同名的 `shape_btn_white_selected.xml`，内容却是语义完全不同的导航选中态背景，颜色与未按下态几乎一致。合并后库选择器的按下态引用被"劫持"到这份同名资源，按钮按下反馈肉眼不可见——昼夜两种模式一起失效。

修复是重命名壳工程的同名资源消除遮蔽，并同步更新全部引用点（39e4afc4 节选自真实 diff）：

```diff
--- application/Setting/src/main/res/layout/layout_seat_adjustment.xml
-                                android:background="@drawable/selector_common_round_btn"
+                                android:background="@drawable/selector_common_white_btn"
--- application/Setting/src/main/java/.../extension/ViewExtension.kt
     fun TextView.setChecked(isChecked: Boolean) {
-    if (isChecked) this.setBackgroundResource(R.drawable.shape_btn_white_selected) else this.setBackgroundResource(
+    if (isChecked) this.setBackgroundResource(R.drawable.shape_btn_white_bg) else this.setBackgroundResource(
         R.drawable.shape_setting_gray_round_bg
     )
 }
```

`shape_btn_white_selected.xml` 重命名为 `shape_btn_white_bg.xml` 后，CommonTools 选择器的按下态引用不再被劫持；座椅按钮同时换用含 `state_pressed` 条目的标准选择器，按下反馈恢复。

预防规则：公共资源命名加模块前缀或统一收口到公共库，壳工程禁止定义与库同名的资源；症状特征是"库组件的交互态只在某些 app 内失效"，极难直觉定位，遇到时直接搜同名资源在哪些模块出现。

**Q9: [learning] "按钮点击无视觉反馈"类缺陷的固定排查套路是什么？selector 还有哪些必查项？**

固定排查顺序有三步：

1. 反查按钮 background 所用 selector 的每个 state 条目分别指向哪个 shape/drawable。
2. 全模块搜索这些资源名，找出每个同名定义的位置。
3. 核对资源合并后实际生效的文件，确认是否被 app 资源覆盖库资源。

SIR-7514 的按下无反馈就是按此顺序定位到同名遮蔽。selector 本身还要检查两类问题：可点按钮需有 `state_pressed` 条目；每个 `pressed`/`selected`/`checked` 状态都要映射到语义相符的资源。缺 pressed 会导致按下无反馈，状态映射错则可能在白天主题中不明显、夜间主题下出现刺眼的“白底未选”外观。两种问题症状相似，但前者是状态缺失，后者是资源映射错误。

**Q10: [learning] selector 状态表复制上一行忘改资源，为什么会表现出"白天正常、黑夜异常"的功能假象？（SIR-6802）**

因为错误映射造成的视觉反差大小随主题变化，症状强度不同就会伪装成"模式相关的功能 bug"。SIR-6802 中公共选择器的 `state_selected="true"` 条目错指向了未选中的白色形状——白天主题下选中态与未选中态的底图反差小，勉强可用；黑夜主题下选中项呈现"白底未选"外观，与暗色背景形成刺眼错位，看起来就像"自动亮度点不动、不可设置"，而亮度功能逻辑本身没有任何问题。

修复是把该条目改指向选中态形状，与 pressed/checked 语义对齐（节选自真实 diff）：

```diff
--- component/CommonTools/src/main/res/drawable/selector_common_gray_btn.xml
     <item android:state_pressed="true" android:drawable="@drawable/shape_btn_white_selected" />
-    <item android:state_selected="true" android:drawable="@drawable/shape_btn_white_unselected" />
+    <item android:state_selected="true" android:drawable="@drawable/shape_btn_white_selected" />
     <item android:state_checked="true" android:drawable="@drawable/shape_btn_white_selected" />
```

因为是公共 drawable，所有复用界面一并修复。判断规则：遇到"白天正常、黑夜异常"先查资源层（selector 状态表、日夜两套资源映射）再查功能代码；公共 drawable 是全局放大器，一行错误影响所有复用页面。

**Q11: [learning] Activity 在 configChanges 里声明接管 uiMode 后，深浅色切换为什么会让界面停在旧主题？（SIR-6360）**

在仍由 Activity 重建处理该配置变化的平台版本上，`configChanges` 是接管声明：声明 `uiMode` 后，日夜切换不再按默认路径销毁重建 Activity，而由应用在 `onConfigurationChanged` 自行处理。Android 17（API 37）对少数配置变化增加了新的重建策略属性；因此具体版本应以目标系统的 runtime-change 规则为准，不能把“声明即永不重建”推广到所有配置与版本。SIR-6360 中一个浮窗 Activity 的 `configChanges` 包含 `uiMode`，但它没有在回调里做任何主题刷新——深色模式下打开的浮窗，系统切到浅色后既不重建也不换肤，弹窗保持打开瞬间的深色资源。修复是从 `configChanges` 中移除 `uiMode`，把主题切换交还系统走标准重建流程，重建时按新主题重新 inflate（节选自真实 diff）：

```diff
--- application/Setting/src/main/AndroidManifest.xml
-            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|uiMode|locale|layoutDirection|touchscreen"
+            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|locale|layoutDirection|touchscreen"
```

采用系统重建时需要权衡两点：

1. 切换主题会重建 Activity，可能短暂闪烁或丢失未保存的临时视图状态；只做展示的浮窗通常可接受。
2. 深浅色验收要遍历 DialogActivity 与浮窗，因为这些隐藏窗口最容易遗漏主题刷新。

**Q12: [learning] WebView 加载的本地协议页为什么 values-night 管不到？标准适配方案是什么？（SIR-4568）**

`values-night` 只作用于 Android 资源系统，HTML 内容由 WebView 自己渲染，App 层主题再正确也改不了网页内部样式——SIR-4568 中 WebView 底色设为透明，HTML 全部样式写死亮色值，黑夜模式下白底直接透出。宿主侧的核心改动是新增一个按 uiMode 取底色的方法（节选自真实 diff）：

```diff
// WebFragment.kt：底色随 uiMode 取值，并开启算法变暗兜底
+    private fun applyWebViewTheme() {
+        val isNightMode =
+            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
+                Configuration.UI_MODE_NIGHT_YES
+        webView.setBackgroundColor(
+            if (isNightMode) Color.rgb(21, 23, 26) else Color.WHITE
+        )
+        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
+            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
+        }
+    }
```

标准方案是双层配合的四件事：

1. **HTML 侧**：声明 `color-scheme: light dark`，把硬编码色重构成 CSS 变量，用 `@media (prefers-color-scheme: dark)` 定义整套暗色调色板；
2. **WebView 底色**：按 uiMode 设置背景色，且与 HTML 的页面底色取同一数值，避免加载瞬间闪白；
3. **算法变暗兜底**：支持时开启 `ALGORITHMIC_DARKENING`，处理未覆盖的亮色内容（注意可能与手写暗色样式叠加二次变暗，需验收）；
4. **运行中切换**：`onConfigurationChanged` 里重取主题并 `reload()`，接受 reload 丢失滚动位置的副作用（静态协议页可接受）。

`Color.rgb(21, 23, 26)` 的三个整数参数分别是红、绿、蓝通道，取值范围为 0–255，组成夜间背景 `#15171A`；`Color.WHITE` 则是白色常量。WebView 设置背景色只是宿主视图露底时的颜色，不会改 HTML 的 CSS。`setAlgorithmicDarkeningAllowed(..., true)` 中 `true` 表示允许对未声明暗色方案的页面算法变暗，省略该调用时默认不允许；调用前用 `isFeatureSupported` 检查当前 WebView 是否支持该功能。该 API 仅对 targetSdk 33 及以上按新规则生效；WebView 会按宿主主题设置 `prefers-color-scheme`，自有页面应优先提供 CSS 暗色方案，并避免算法变暗叠加造成颜色二次变化。`--page-background: #15171A` 与宿主夜间背景相同，能避免加载露底时闪白。

**Q13: [learning] 弹出 Dialog 时为什么 Dock 栏等"别的窗口"也会被压暗？替代方案是什么？（SIR-6606）**

因为 Dialog 的 `backgroundDim` 是 WindowManager 施加在弹窗所在层级之下整个区域的窗口级遮罩，作用范围不以应用页面为边界。多窗口车机中 SystemUI 的 Dock 是独立窗口，与弹窗宿主同层，于是系统遮罩把 Dock 一起盖暗（SIR-6606：扫码登录弹窗导致 Dock 栏变深色）。修复思路是"去系统遮罩、改页内自绘"：给弹窗增加 `backgroundDimEnabled=false` 的无遮罩主题，遮罩改为宿主布局内部的半透明 View，只覆盖自身内容。弹窗构造用默认参数保留旧行为（节选自真实 diff）：

```diff
-class QrCodeLoginDialog(context: Context) : Dialog(context, R.style.QrCodeLoginDialogTheme) {
+class QrCodeLoginDialog(
+    context: Context,
+    dimBehind: Boolean = true
+) : Dialog(
+    context,
+    if (dimBehind) R.style.QrCodeLoginDialogTheme else R.style.QrCodeLoginDialogNoDimTheme
+) {
```

构造参数 `dimBehind` 控制是否使用带系统 dim 的主题，省略时 Kotlin 默认值 `true` 保留原行为；`if` 按该布尔值选择两个主题资源，参数若省略或为 `true` 仍启用原遮罩，显式传 `false` 才选择无遮罩主题。主题中的 `backgroundDimEnabled=false` 关闭 Window 背后的系统遮罩。配套要点如下：

1. **遮罩 View 全生命周期联动**：显隐与弹窗 show、dismiss、cancel、`onPause`、销毁全部联动，否则产生"弹窗没了遮罩还在"的新残留。
2. **用默认参数保留旧行为**：构造参数 `dimBehind = true`，强制登录等确实需要系统遮罩的场景不受影响，调用方逐个迁移。
3. **先问遮罩由谁施加**：这类跨窗口 UI 污染问题的第一反应是"遮罩的层级和范围由谁施加"，而不是改弹窗颜色。

**Q14: [learning] 全屏应用手势退出时先闪一帧白底再露出桌面，根因与修复是什么？（SIR-6788）**

根因是窗口不透明加退出动作过重两层叠加：主题里 `windowBackground` 与 `statusBarColor` 都是白色，Activity 退出或重建的瞬间白底窗口先于内容暴露，形成"全白"一帧；退出实现用 `moveTaskToBack` + `finishAffinity` 整栈销毁，中间经历"白底窗口 → 桌面"的重绘间隙才闪现下层桌面。修复双管齐下：主题透明化（`windowIsTranslucent=true`、`windowBackground`/系统栏全透明），让下层桌面始终透出；退出语义收敛为单 Activity 的 `finish()`，直接露出其下界面，过渡连续。主题层改动（节选自真实 diff）：

```diff
--- application/AccountCenter/src/main/res/values/themes.xml
-        <item name="android:statusBarColor">@color/white</item>
-        <item name="android:windowBackground">@android:color/white</item>
+        <item name="android:statusBarColor">@android:color/transparent</item>
+        <item name="android:windowIsTranslucent">true</item>
+        <item name="android:windowBackground">@android:color/transparent</item>
```

`statusBarColor` 控制状态栏底色，`windowBackground` 控制窗口内容尚未绘制时的底色；设为 `@android:color/transparent` 后对应区域透明，省略时继续使用主题继承/默认值。`windowIsTranslucent=true` 声明窗口半透明，省略或设为 `false` 时按不透明窗口处理；它会影响窗口合成和部分优化，也可能影响动画与焦点。`finish()` 只结束当前 Activity，不清理任务栈中残留的其他页面，多级页面需确认各自正确结束。判断规则：车机上"全屏应用浮在 3D 桌面上"的场景，主题必须透明化，否则任何窗口切换瞬间都会闪底色；手势返回单页退出用整栈销毁手段往往过重。

**Q15: [learning] 界面顶部或四周露出异色色块，检查哪三处可以定位大多数案例？（SIR-7382）**

按背景绘制层级排查三处：

1. **背景所在层：**全屏背景应放在根布局或 Window background；内容层带 elevation 时，背景与阴影可能一起抬升，根布局未铺底就会露出窗口底色。
2. **父容器透明度：**检查根布局及中间父容器是否透明；透明区域会显示其下层窗口或系统表面。
3. **Z 轴与阴影：**核对 elevation 是否让内容容器浮于父布局之上并投射阴影轮廓。

SIR-7382 中全屏背景设在 `elevation="32dp"` 的内容层容器上，根布局没有背景，窗口底色从顶部两侧透出；把背景移到根布局后，窗口铺底而内容容器阴影保留。`elevation="32dp"` 表示 32dp 的 Z 高度，省略时 elevation 默认为 0dp，容器不因该属性抬高。

**Q16: [learning] 搜索结果列表写在 ConstraintLayout 里却用了 layout_height="match_parent"，为什么界面只露出最后一行，约束布局里该写什么？**

ConstraintLayout 中应使用 `0dp`（`MATCH_CONSTRAINT`）让尺寸由约束决定；`match_parent` 按父容器尺寸测量，不会表达“填满两侧约束之间的区域”。该案例的列表因此超出预期显示区，只露出最后一行，容易被误判为数据只同步了一条。

某车机项目 SIR-2895 案例（B 级，低概率 10%~40%）：蓝牙电话搜索结果列表 `recyclerViewMatches` 位于 ConstraintLayout 内，却写了 `android:layout_height="match_parent"` 并用上下 `50dp` 的 margin 留白，导致它没有按上下约束填充设计区域，列表超出 `tabLayout` 以下的预期范围而错位。修复把高度改为 `0dp`（匹配约束）、把该案例中用于内容内缩的留白改为 padding（节选自真实 diff）：

```diff
--- application/BTPhone/src/main/res/layout/activity_main.xml
         <androidx.recyclerview.widget.RecyclerView
             android:id="@+id/recyclerViewMatches"
             android:layout_width="match_parent"
-            android:layout_height="match_parent"
+            android:layout_height="0dp"
             android:paddingLeft="30dp"
-            android:layout_marginTop="50dp"
+            android:paddingTop="50dp"
             android:paddingRight="20dp"
-            android:layout_marginBottom="50dp"
+            android:paddingBottom="50dp"
```

片段中 `android:id="@+id/recyclerViewMatches"` 为此列表声明 ID，供约束和代码引用；`android:layout_height="0dp"` 将纵向尺寸交给 ConstraintLayout 上下约束。`android:layout_width="match_parent"` 是原始横向配置，按父容器测量；若横向也由左右约束决定，ConstraintLayout 官方建议同样用 `0dp`。左右 padding `30dp`、`20dp` 是内容内边距；将上下 `50dp` 从 margin 改为 padding 后，外部留白变成内部留白，列表外框仍按约束铺开。代码片段省略了约束属性；只有上下约束足以确定高度时，`0dp` 才会填满目标区。

边界：`0dp` 需要有足以确定该轴尺寸的约束；仅有一侧约束时，另一侧行为还取决于内容与 ConstraintLayout 版本。若背景要铺满外框而内容需要缩进，用 padding；若要让 View 与邻项或约束边界隔开，用 margin。遇到内容截断或错位，先测实际 View 边界与约束区域，再检查数据条数。

**Q17: [learning] 带全屏遮罩的密码弹窗被键盘整体顶起、遮罩盖住状态栏，正确的避让方式是什么？（SIR-7359）**

错误姿势是依赖窗口级 `softInputMode`（`adjustResize` 或 `adjustPan`）：前者请求系统因 IME 调整窗口可用区域，后者请求平移窗口，整窗变化会连同全屏遮罩一起影响。该案例改用 `SOFT_INPUT_ADJUST_NOTHING`，即不让系统因 IME 自动调整窗口；应用再用 `WindowInsetsCompat.Type.ime()` 读取 IME inset，只对内容卡片计算负向 `translationY`，遮罩保持全屏。实际 inset 可见性与数值应结合窗口边到边配置和目标 Android 版本验证。

`SOFT_INPUT_ADJUST_NOTHING` 是 `WindowManager.LayoutParams` 的 soft-input 调整模式常量；省略或改用其他模式时，系统按窗口属性/主题指定的模式处理 IME。`WindowInsetsCompat.Type.ime()` 选择 IME 对应的 inset 类型，`translationY` 是只移动内容卡片的垂直像素偏移，负值向上。关闭过程中冻结避让计算，避免键盘先收起导致卡片复位闪动；`onDestroyView` 移除布局监听以防泄漏。该公共基类方案适用于带全屏遮罩且需要独立移动内容的对话框。

**Q18: [learning] 设计稿指定 450、550 这类非整百字重时，为什么仍可能无法还原？该怎样判断问题在规格还是字体？**

Android 28 起，Typeface 可请求 1–1000 的字重，因此 450、550 并非框架不支持；实际能否得到相应外观，取决于所用字体是否提供对应静态字重或可变字体轴，以及系统/字体匹配能否提供最接近的字形。若字体只有 400、500 等离散字重，平台可能选取可用字重，不能凭请求值保证像素级还原。

1. **核对字体能力：**确认设计稿指定的字重是否存在于项目字体文件；可变字体还要确认字体的 weight 轴范围包含目标值。
2. **核对平台路径：**`Typeface.create(family, weight, italic)` 从 API 28 起接受 1–1000 的目标值；低版本需要兼容实现，不能假定同一调用可用。
3. **收敛设计规格：**字体没有目标字重时，与设计确认采用最接近的已提供档位，或交付含该字重的字体文件；不要把“请求了 450”误当成“实际字形就是 450”。

该项目中的字重偏差应先定位到字体文件/匹配结果，再决定是否调整规格；只有目标字重超出可用字体能力时，才是设计交付与字体资源需要协商的边界。

**Q19: [learning] 快速连续切换深浅色导致屏幕出现灰色中间态，为什么要在入口防抖而不是处理重绘结果？（SIR-8546）**

因为异常发生在重建风暴，事后恢复无从下手。主题切换内部走 UiModeManager 触发系统级 configuration change 与全局重建重绘；连续快速点击时多次切换请求叠加，上一次重建尚未完成新的 uiMode 变又到达，系统在中间态重绘出未完成配色的界面——表现即灰色显示。修复在触发源限流：切换入口加 1000ms 防抖，窗口内的重复点击被拒绝（节选自真实 diff）：

```diff
// DisplayFragment.kt：防抖窗口内拒绝请求并回设已生效主题
         mBinding.rgDisplayMode.onItemChecked { position, _ ->
+            val now = System.currentTimeMillis()
+            if (now - displayModeSwitchStartedAt < DISPLAY_MODE_SWITCH_DEBOUNCE) {
+                mBinding.rgDisplayMode.setSelectedIndex(
+                    SettingsUtils.getGSetting(THEME_SHOW_MODE, MODE_AUTO)
+                )
+                return@onItemChecked
+            }
+            displayModeSwitchStartedAt = now
             mViewModel.setDisplayMode(requireContext(), position)
         }
```

`DISPLAY_MODE_SWITCH_DEBOUNCE` 是防抖窗口，案例值为 1000 毫秒；`now` 与 `displayModeSwitchStartedAt` 都是毫秒时间戳，差值小于窗口时拒绝本次请求。被拒绝时 `setSelectedIndex(...)` 把控件选中项回滚到持久化设置 `THEME_SHOW_MODE` 的值，`MODE_AUTO` 是读取失败或键不存在时使用的回退值；`return@onItemChecked` 结束当前回调，不触发 ViewModel 切换。窗口外记录新开始时间，再把回调给出的 `position`（当前选项索引）传给 `setDisplayMode`。因此，时间戳字段的生命周期必须与所需的跨实例防抖范围一致；如果只在一个 Fragment 内有效，实例字段即可，静态字段会扩大到所有实例并需处理并发访问。

**Q20: [learning] 长歌名跑马灯滚动 3 遍才停，是哪个默认值造成的？（SIR-1477）**

`TextView` 的 `marqueeRepeatLimit` 在平台实现中的默认重复次数为 3；省略该属性时沿用默认值，因此文本会滚动三遍。显式设为 `1` 表示重复一次，满足该 UI 只滚一遍的要求；省略时不会自动变成一次。此默认值可在 Android `TextView` 平台实现中核对，项目若使用厂商修改过的框架仍应实机确认。

跑马灯还要求 TextView 获得焦点、启用 marquee（通常通过 `ellipsize="marquee"`）、限制为单行且文本宽于可视区域；若布局显式设了 `marqueeRepeatLimit`，该值覆盖平台默认值。排查不滚时先核对这些前提，每个需要单遍的 TextView 都要单独设 `android:marqueeRepeatLimit="1"`。判断规则："跑马灯次数不对"优先查 `marqueeRepeatLimit`，这类平台默认值与产品预期不一致的属性，应写进新页面模板而不是靠踩坑发现。

**Q21: [learning] 刻度进度条/滑块与 UI 或实际行为不符，有哪些典型的 off-by-one 形态？**

两种方向相反的形态，共同根源是档位定义没有从值域推导：

1. **档位数少 1**：控件档位总数硬编码为最大值本身（gearCount=20），而实际值域含 0 档共 21 个取值，UI 的"最后一格"只映射到 19，永远调不到最大值（SIR-1472，"拉满却没满"）；
2. **区间不匹配**：UI 的 0 基档位值原样下发给 1 基的信号区间（合法 1~20，0 非法），极值下发被底层拒绝或纠偏后回调刷新 UI，表现为拖到最低"回弹"（SIR-5708，修复是收发两侧成对 +1/-1 换算）。

判断规则："档位数 = 最大值 + 1"，硬编码前先推导值域而不是照抄视觉稿的格子数；修"回弹"类问题的第一线索是 UI 发出的极值是否落在合法区间内，先对区间再查动画。多个界面共用同一自定义刻度控件时，档位这类参数收敛到控件默认值或统一常量，避免两处数字各自漂移。

**Q22: [learning] 回弹/复位动画从错误位置跳变，根因是什么？（SIR-6634）**

动画起点取错了坐标系。下滑退出手势在未达阈值松手时执行回弹动画，参数传的是本次手势的增量（`currentY - initialY`），而视图当前可能已停在前次下滑留下的位移上——动画从"增量值"起算，与视图实际位置脱节，随后的反向滑动中视图从错误位置突变"弹起"。修复是把回弹起点改为视图当前真实位移（`getCurrentTranslation()`），与跟手逻辑、退出动画的取值口径统一（节选自真实 diff）：

```diff
--- component/CommonTools/src/main/java/.../utils/LapseTouchHelper.kt
                     if (isOverThreshold) {
                         isTriggered = true
                         animationToExit(currentY)
                     } else {
-                        animationToStart(deltaY)
+                        animationToStart(onExitListener.getCurrentTranslation())
                     }
```

判断规则：动画的起点必须取视图当前状态（translation/scroll），不能取手势增量或事件坐标——两者坐标系不同，混用必然跳变。测试上"滑一半松手 → 反向滑动"是下滑退出交互的高频边界场景，用例应显式包含。

**Q23: [learning] 多个界面滚动条样式不一、与设计稿不符，治理方案是什么？（SIR-7004）**

根源是组件属性没有抽公共样式：列表只声明 `android:scrollbars="vertical"` 打开垂直滚动条，省略时采用 View 的默认滚动条设置；仅声明方向会沿用平台默认 thumb 外观。治理时新增统一 thumb drawable，再通过 `android:scrollbarThumbVertical` 指向它；后者指定垂直 thumb 资源，省略时由系统样式提供。颜色引用日夜 token 后，同一份 drawable 可随主题变化。最后全仓库排查存量默认 thumb 的列表。

判断规则：滚动条、分割线、按钮这类细碎视觉件应沉淀为统一 drawable/style，"与 XX 界面不一致"类缺陷的根源往往是同类组件各配各的；新页面模板内置这些属性，避免每个列表再裸声明 scrollbars。

**Q24: [learning] 图标颜色依赖主题色资源导致个别场景与设计稿不符，为什么修复反而要新增一份硬编码色值的专用资源？（SIR-7406）**

因为共用资源的主题化取值无法满足单场景的精确视觉要求。通用加载图用主题色资源加透明度渐变实现灰阶，颜色实际值依赖主题解析——在格式化 Loading 这个特定场景解析出的色值与设计稿指定的固定灰阶不符。修复不是原地改共用图（会波及所有使用方），而是新增一份按设计稿色值固化的专用矢量图，并把该场景的引用切换过去；旧图不动，其他场景不受影响。

判断规则：对视觉有精确要求的关键图（加载图标、品牌色图形），用硬编码色值的专用 drawable 比共用主题色资源更可控；修复"共用资源被单场景绑架"时，"新增专用资源 + 切换引用"优于修改共用资源。代价是同形资源出现两份，后续形状调整需同步，属可接受取舍。

**Q25: [learning] 设计稿版本混淆与需求变更，如何在流程上防回归？（SIR-4333、SIR-7849）**

两类典型事故划定治理范围：一是变更后只删一半——需求移除渐变遮罩时，布局里的遮罩 View 与代码里的滚动联动调用必须成对清理，漏掉联动调用就残留无效逻辑或编译错误（SIR-4333）；二是新 UI 落地不全——协议新增了驾驶模式选项，界面既缺式样也缺 Tab 项，靠缺陷单"按最新 UI 修改"逐个补（SIR-7849）。两者共同根源是实现与设计稿基线脱节。

流程上用四项措施防回归：

1. 装饰性图层通过独立 style/drawable 收敛，变更时优先替换资源，减少布局返工。
2. 实现与验收都以提测基线设计稿为准，UI 变更必须走需求流程，不能沿用旧稿“顺手改”。
3. UI 新需求（尤其深浅色素材和新增模式图标）在提测清单中逐项对照最新稿。
4. 提测矩阵覆盖日夜与分辨率组合、冷启动首屏，以及 DialogActivity 和浮窗。

**Q26: [learning] 需求或 SRS 变更后，为什么"删掉的没清干净、增量的没逐项落地"是同一类缺陷，验收该以什么为 checklist？**

因为变更是双向的而验收通常是单向的——评审只确认"新加的东西在不在"，不确认"删掉的东西清没清"。两条 checklist 应分别来自 SRS/设计稿的删除条目与新增条目，逐项打勾，而不是凭 diff 大小判断"改过了"。

某车机项目需求变更与理解偏差 53 条修复里，删除侧与新增侧各有成规模的形态。删除侧的典型是清理死资源时暴露的多语言目录失配：SIR-1256 在删除 BTMusic 已无引用的字符串资源时，发现英文目录里 `pre`/`next` 的值仍是中文“上一首/下一首”，切到英文环境后按钮仍显示中文；文件结尾长期缺少换行符也侧面说明它缺乏维护。同一批清理里，SRS 已删除的头盔图标、文案和样式仍留在代码里。新增侧包括 SRS 写明的副标题未添加、置灰态缺 toast、系统按键缺提示音、缺二次确认弹窗，以及实现语义与 SRS 不符：能量回收等级置灰逻辑不一致、哨兵模式预约首次上电为空（需求为 22:00–8:00）、未插 U 盘时存储空间未显示“--”。

验收清单按变更方向拆为三项：

1. 删除侧：全局检索资源引用，尤其检查 `layout` 和 data binding；只凭 IDE 显示“未引用”可能漏掉运行时访问。
2. 多语言侧：资源存在 `values-*` 目录时，同步核对默认目录与每个 locale；CI 可检查各 locale 键集合和实际文案语言。
3. 新增侧：从 SRS 条目生成 checklist，逐项检查文案、图标、副标题、toast、二次确认和置灰态是否落地。

**Q27: [learning] 业务代码里用 uiMode == 19 这类数值比较判断夜间模式再手动选颜色，为什么是黑白适配的反模式？正确写法是什么？**

`Configuration.uiMode` 把 UI 类型位与夜间位组合在一个整数中，十进制 `19`（十六进制 `0x13`）对应 `UI_MODE_TYPE_CAR`（`0x03`）与 `UI_MODE_NIGHT_NO`（`0x10`），不是“夜间模式”标志。直接与 `19` 比较会把类型和夜间状态绑死，换成夜间值或其他 UI 类型就不匹配；更根本的是它把本该由资源系统处理的颜色选择搬进业务代码，而且只在设置颜色时判断一次，不响应后续模式变化。Setting 黑白适配清理提交把这类分支整体删除，颜色引用改为语义色令牌（节选自真实 diff）：

```diff
// CustomEditDialogFragment.java：删除魔数日夜分支
-                    int uiMode = requireContext().getResources().getConfiguration().uiMode;
-                    if (uiMode == 19) {
-                        btConfirm.setTextColor(getResources().getColor(R.color.text_default_color));
-                    } else {
-                        btConfirm.setTextColor(getResources().getColor(R.color.white));
-                    }
+                    btConfirm.setTextColor(getResources().getColor(R.color.text_default_default));
```

删除后颜色完全交给资源限定符在 uiMode 变化时自动解析。确实需要代码判定的场景（如按电量值拼资源名取帧图这类动态资源名），用掩码位与：`uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES`，永不与组合字面值比较。判断规则：见到 uiMode 数值比较就是适配隐患，颜色分支一律收敛为语义色引用。

**Q28: [learning] 公共库同时存在新旧两套色板、硬编码引用散落各处时，怎样的重构顺序能保证旧色引用零残留？**

直接删除整套旧色名逼出所有残留引用——编译器会逐个暴露仍在引用旧色名的文件，比全局搜索替换可靠。CommonTools 的色板收口提交正是这个做法：整体删除旧 `values/colors.xml`（37 行以色值命名的旧色）与只覆盖 4 个颜色的旧 `values-night/colors.xml`，再把 selector、dialog/toast 布局、按钮背景的引用统一替换到 `text_default_default` 这类"语义_状态"命名的新令牌（节选自真实 diff）：

```diff
--- component/CommonTools/src/main/res/values/colors.xml（整个文件删除）
-    <color name="text_default_color">#20232B</color>
-    <color name="setting_warning">#DD5252</color>
--- component/CommonTools/src/main/res/values/view_styles.xml
-        <item name="android:textColor">@color/text_default_color</item>
+        <item name="android:textColor">@color/text_default_default</item>
```

"先收敛色板、再改引用"的顺序是关键：旧库 values 与 values-night 两份并存且命名无语义，直接在引用处替换必然遗漏。代价是删除公共库色属于破坏性变更，同批所有引用方必须一起合入（该批次同日 8 个"黑白模式适配"提交就是同一次收敛的分布式落地）。换引用时令牌按语义选、按语义用——拿按压态令牌顶替正文次要色虽能跑，语义错位是下一轮走查的新 bug 源。判断规则：跨模块颜色重构用"删定义逼引用"，新颜色直接用语义令牌，不留以色值命名的私有色。

**Q29: [learning] 用 ChangeSkinManager.getColorResource("text_default_color") 这类按字符串资源名运行时取色的换肤框架，为什么要迁移到 R.color 常量引用？**

字符串资源名是运行时映射：编译期不可查，黑白映射表要人工维护，资源改名或漏维护映射只会静默取不到值；换成 `R.color.xxx` 常量引用后编译器兜底，夜间值由 `values-night` 自动提供，换肤框架维护的皮肤包映射表失去存在必要。公共控件 SkinSwitchCardView 的迁移就三行（节选自真实 diff）：

```diff
// SkinSwitchCardView.java：字符串名运行时映射改为 R 常量
-        textTitle.setTextColor(ChangeSkinManager.getInstance().getColorResource(getContext(),"text_default_color"));
+        textTitle.setTextColor(ResourceUtils.getColor(R.color.text_default_default));
```

迁移可以逐控件进行，不需要一次性切换框架——编译通过即迁移正确（该控件当时的 switch 轨道仍走旧框架，两套机制允许短期并存）。收尾两件事：类名里残留的 "Skin" 与实际机制不符应重命名；换肤框架失去使用方后整体下线，省一份运行时反射开销。判断规则：凡"按名字取资源"的路径（`getIdentifier`、字符串换肤框架）都有同样的静默失效面，迁移方向都是编译期可查的 R 常量。

**Q30: [learning] 页面确需避免主题切换时重建、必须在 configChanges 里保留 uiMode 时，要补什么才不会重演"停在旧主题"？**

接管 uiMode 等于承诺自建完整刷肤路径——在 `onConfigurationChanged` 里逐控件重设语义资源，漏一个控件那个控件就停在旧主题。Setting 配对弹窗 Activity 的实现是"正确接管"的完整样本：根布局遮罩、卡片、PIN 码格、确认/取消按钮共 12 处逐一重设（节选自真实 diff）：

```diff
// PairDialogActivity.kt：uiMode 变化时手动刷肤
+    override fun onConfigurationChanged(newConfig: Configuration) {
+        super.onConfigurationChanged(newConfig)
+        setBackgroundResource(mBinding.root, R.drawable.bg_dialog_dim)
+        setBackgroundResource(mBinding.tvConfirm, R.drawable.selector_common_black_btn)
+        mBinding.tvConfirm.setTextColor(ResourceUtils.getColor(R.color.selector_common_text_color_white))
+        setBackgroundResource(mBinding.tvCancel, R.drawable.selector_common_white_btn)
+        // …（节选自真实 diff：PIN 格背景与文字等其余控件）
+    }
```

按页面状态成本选择换肤路线：

1. 页面轻且状态易恢复（如表单或展示浮窗）时，移除 `configChanges` 中的 `uiMode` 并使用资源 token，让系统重建。
2. 页面状态难恢复（如播放过程）时，才接管 `uiMode` 并实现手动刷肤；每个控件都必须在刷新函数中更新。

手动清单会在控件增删时失同步，可沉淀为统一的 `refreshTheme()`；成对的黑/白 selector 也能让按钮主题切换只改引用。

**Q31: [learning] 布局声明了 textFontWeight，代码又对同一批文本 setTypeface(Typeface.DEFAULT_BOLD)，字重为什么会失控？应从哪几处收敛？**

XML 的 `textFontWeight` 与代码的 `setTypeface` 是两套字重控制点，运行时 `setTypeface` 调用会覆盖布局声明——"代码设 DEFAULT_BOLD（700 档）+ 布局声明 400"叠加的观感就是明显偏粗，两处各自调整只会互相打架。蓝牙电话 tab 选中项过粗即此：修复同时移除三处 `setTypeface` 调用与布局里的 `textFontWeight` 属性，字重回归字体文件默认渲染，选中态改由颜色令牌区分（节选自真实 diff）：

```diff
// MainActivity.java：删除运行时字重覆盖（修复以注释方式移除，更佳做法是直接删除）
-            binding.tvContacts.setTypeface(Typeface.DEFAULT_BOLD);
-            binding.tvFavorites.setTypeface(Typeface.DEFAULT);
-            binding.tvCallLog.setTypeface(Typeface.DEFAULT);
```

结合字体能力一起理解：即使布局请求某个数值字重，运行时 `setTypeface` 仍会覆盖这项字体选择；若字体没有对应字重，单靠调整调用位置也无法造出该字形。判断规则：同一文本的字重只保留一个控制点——要么布局字体属性、要么运行时 `setTypeface`，二选一；选中态表达优先用颜色与指示器，不靠加粗；删除代码优于注释保留。


**Q32: [learning] 搜索方法收到空关键字时，怎样避免进入普通查询路径？**

空关键字通常会匹配大量记录或进入无意义的查询路径；如果页面没有为该状态定义结果语义，继续执行会让列表和“无匹配”提示状态失去约束。SIR-2895 的 `onSearch()` 曾把空串判定注释掉，恢复守卫后，空输入在搜索入口结束处理：

```java
public void onSearch(int currentTab, String numberStr) {
    if (TextUtils.isEmpty(numberStr)) {
        return;
    }
    // …（节选自真实 diff：后续查询逻辑未展示）
}
```

`currentTab` 表示当前搜索分类，`numberStr` 是本次输入；守卫只检查输入是否为空，空时 `return` 阻止后续查询，非空时继续执行。该代码是摘录，省略号表示真实查询逻辑未展示。若产品需要空关键字展示默认内容，应在此处显式定义该行为，而不是让空串偶然进入普通搜索。

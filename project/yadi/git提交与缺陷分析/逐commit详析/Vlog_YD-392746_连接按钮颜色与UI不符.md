# YD-392746 · 连接按钮颜色与UI不符

- **提交**：`1a1d1bd5` | 2026-06-27 | daizhecheng | Vlog | bugfix（UI 视觉修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
相机配对页连接/断开按钮在断开态（描边底）上文字仍是白色，白字配白/透明底几乎不可见，按钮颜色与 UI 设计稿不符。

## 根因分析
`CameraPairedActivity.updateConnectText()` 旧实现只切了 `btnConnect.text` 和 `rlConnect.background`，从未设置 `setTextColor`——文字颜色永远停留在布局里写死的值。而 5b443500 引入的断开态背景 `btn_bg_radio18_circle` 是"白色填充 + `#1A1F222A` 描边"的固定 300x60dp vector，白底配白字必然"看不清/颜色不对"；且 vector 尺寸写死，与按钮实际尺寸不符。这是一个"状态切换不完整"问题：按钮有三要素（文字、底、字色），旧代码只同步了两项。

## 关键代码修改
改动文件：application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt、res/drawable/btn_bg_radio18_circle.xml、res/layout/camera_pair_activity.xml、build.gradle

```diff
--- application/Vlog/.../main/ui/CameraPairedActivity.kt
@@ updateConnectText 三要素同步切换
+        if (viewModel.isConnected) {
+            mBinding.let {
+                it.btnConnect.text = getString(R.string.btn_disconnect)
+                it.btnConnect.setTextColor(resources.getColor(R.color.text_white_default, null))
+                it.rlConnect.background = getDrawable(R.drawable.btn_bg_radio18_red)
+                it.ivLoading.visibility = View.GONE
+            }
+        } else {
+            mBinding.let {
+                it.btnConnect.text = getString(R.string.btn_connect)
+                it.btnConnect.setTextColor(resources.getColor(R.color.gray_950, null))
+                it.rlConnect.background = getDrawable(R.drawable.btn_bg_radio18_circle)
+                it.ivLoading.visibility = View.GONE
+            }
+        }
```

```diff
--- application/Vlog/src/main/res/drawable/btn_bg_radio18_circle.xml
@@ 白底 vector → 透明描边 selector
-<vector ... android:width="300dp" android:height="60dp">
-    <group><clip-path .../><path android:fillColor="#FFFFFF" .../>
-        <path ... android:strokeColor="#1A1F222A" .../></group>
-</vector>
+<selector ...>
+    <item><shape android:shape="rectangle">
+        <stroke android:width="2dp" android:color="@color/divider_default" />
+        <corners android:radius="18dp" />
+        <solid android:color="@android:color/transparent" />
+    </shape></item>
+</selector>
```
另：`camera_pair_activity.xml` 文字色换令牌（`@color/black` → `@color/text_default_default`、`@color/white` → `@color/text_white_default`）；`build.gradle` 移除 `NsrCommonUI.aar` 依赖（回退 98763f37 引入的依赖）。

## 为什么能修复
双管齐下：字色随连接态显式切换（断开态 `gray_950` 深灰字），底图改为透明底 + `divider_default` 描边的 shape selector，深灰字在透明/描边底上清晰可读，红底白字与描边深字的对比关系与设计稿一致；shape selector 取代固定尺寸 vector 后自适应按钮大小，还顺带预留了 pressed 态。移除 `AnimationUtils.pause()` 后断连事件只走 `hideLoadings()`，避免动画被暂停在中间帧。隐患：`resources.getColor(id, null)` 未走主题解析，建议 `ContextCompat`/`MaterialColors`。

## 复盘与经验
- **可切换状态的控件要列全视觉属性**：文字/底图/字色/图标四件套，切状态时漏一项就是本类 bug；抽成 `render(state)` 单函数可杜绝。
- **背景资源尽量用 shape selector 而非固定尺寸 vector**：前者自适应、支持按压态、颜色可走令牌；vector 适合图标不适合按钮底。
- **新旧资源风格要成对迁移**：`btn_bg_radio18_circle` 从白底改成透明描边后，依赖"白底"假设的字色必须同步改，否则按下不表、按起暴露。

# YD-392742 · "搜索中"缓存，状态与UI不符

- **提交**：`5b443500` | 2026-06-27 | daizhecheng | Vlog | bugfix（UI/文案状态修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
Vlog 相机配对界面在"搜索/连接中"状态时，按钮文案停留"搜索中"、底色和加载图标与 UI 设计稿不符；断开态按钮背景用的是蓝色矩形底而非设计稿的白色描边圆角底。

## 根因分析
状态展示链路上有三处与设计稿脱节：其一，字符串资源 `btn_search_device` 的语义是"搜索中"，而该状态实际发生在"连接"过程中，文案缓存了早期交互定义，未随交互改版更新；其二，`CameraPairedActivity` 中未连接态背景引用 `R.drawable.btn_bg_radio18_blue`，而设计稿要求白底描边圆角样式；其三，加载图标复用了旧的 `loading_yd`，布局中按钮文字 `match_parent` + 固定 marginStart 的组合在 loading 图标隐藏时无法居中，视觉上"状态与 UI 不符"。

## 关键代码修改
改动文件：application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt、res/drawable/btn_bg_radio18_circle.xml（新增）、res/drawable/ic_black_loading.xml（新增）、res/drawable/ic_white_loading.xml（新增）、res/layout/activity_home.xml、res/layout/camera_pair_activity.xml、res/values/strings.xml、res/values-en/strings.xml

```diff
--- application/Vlog/src/main/java/.../CameraPairedActivity.kt
@@ 断开态按钮底色改为新描边样式
             it.rlConnect.background =
                 if (viewModel.isConnected) getDrawable(R.drawable.btn_bg_radio18_red) else getDrawable(
-                    R.drawable.btn_bg_radio18_blue
+                    R.drawable.btn_bg_radio18_circle
                 )
```

```diff
--- application/Vlog/src/main/res/values/strings.xml
@@ 状态文案纠正
-    <string name="btn_search_device">搜索中</string>
+    <string name="btn_search_device">连接中…</string>
```

```diff
--- application/Vlog/src/main/res/layout/camera_pair_activity.xml
@@ loading 图标与文字居中方案
-                        android:src="@drawable/loading_yd"
+                        android:src="@drawable/ic_black_loading"
@@
-                        android:layout_width="match_parent"
+                        android:layout_width="wrap_content"
@@
-                        android:layout_marginStart="@dimen/dp8"
+                        （margin 移到 iv_loading 上，容器加 android:gravity="center"）
```

## 为什么能修复
三处同源问题一起收敛：文案改成与实际行为一致的"连接中…"；断开态引用新切的 `btn_bg_radio18_circle`（300x60dp vector，白色填充 + `#1A1F222A` 描边圆角 14px）；loading 图标按底色拆成 `ic_black_loading`/`ic_white_loading` 两个新矢量，配 `wrap_content` + `gravity=center` 让"图标+文字"在图标隐藏时仍整体居中。隐患：`btn_bg_radio18_circle` 是固定 300dp 宽的 vector，按钮若改尺寸需同步改资源；values-en 的 `btn_search_device` 也仍是中文文案（该文件本身就用中文占位），英文环境未真正本地化。

## 复盘与经验
- **状态文案是"缓存"最顽固的地方**：交互改版后字符串资源最容易被漏改，review 时要把"每个状态字符串"对照新设计稿过一遍。
- **按钮内"图标+文字"的居中**：固定 `match_parent` 文本 + 图标 margin 的老写法，在图标显隐时会偏移；用 `wrap_content` + 容器 `gravity=center` 才能对任意状态自适应。
- **加载图标按前景色成对切图**（黑/白两版），比运行时染色（tint）更稳，但要注意维护两份 path。

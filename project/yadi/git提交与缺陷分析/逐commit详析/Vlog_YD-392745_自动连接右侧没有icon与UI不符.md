# YD-392745 · 自动连接右侧没有icon与UI不符

- **提交**：`8d784149` | 2026-06-27 | daizhecheng | Vlog | bugfix（UI 视觉修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
自动连接确认弹窗（HintConfirmDialog）文案"自动连接"右侧缺少 UI 设计稿要求的 info 说明图标，与设计稿不符。

## 根因分析
`HintConfirmDialog` 的构造参数只有 `showSwitch`（是否显示"自动连接"开关），布局 `dialog_success.xml` 里根本不存在 info 图标控件——设计稿新增的图标在控件和布局两层都"没有承载位"。这是典型的"设计稿新增元素、控件无扩展点"问题，与 69a46856（isShowThumb）同模式：需要给通用弹窗控件加能力，而不是改调用方。

## 关键代码修改
改动文件：application/Vlog/src/main/java/com/yadea/vlog/main/ui/dialog/HintConfirmDialog.kt、application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt、res/drawable-mdpi/icon_info.png（新增，二进制图片）、res/layout/dialog_success.xml

```diff
--- application/Vlog/.../main/ui/dialog/HintConfirmDialog.kt
@@ 新增 showInfo 参数与图标控制
     private val showSwitch: Boolean = true,
+    private val showInfo: Boolean = true,
 ) : BaseDialog() {
+    private var ivIconInfo: ImageView? = null
@@ initView
         dialogSwitch = view.findViewById(R.id.dialog_switch)
+        ivIconInfo = view.findViewById(R.id.iv_icon_info)
         dialogSwitch?.visibility = if (showSwitch) View.VISIBLE else View.GONE
+        ivIconInfo?.visibility = if (showInfo) View.VISIBLE else View.GONE
```

```diff
--- application/Vlog/src/main/res/layout/dialog_success.xml
@@ 文案右侧补图标位
+        <ImageView
+            android:id="@+id/iv_icon_info"
+            android:layout_width="22dp"
+            android:layout_height="22dp"
+            android:layout_marginStart="@dimen/dp12"
+            android:background="@drawable/icon_info" />
```
调用方 `CameraPairedActivity` 构造自动连接弹窗时显式传 `showInfo = true`。

## 为什么能修复
布局补上 `iv_icon_info`（22dp，`icon_info.png`，marginStart=12dp）使设计稿图标有了渲染载体；`HintConfirmDialog` 以带默认值的 `showInfo` 参数暴露开关，不破坏既有调用点（默认 true 即显示），自动连接弹窗显式传参表达意图。无行为副作用，纯展示扩展；注意图标用 `android:background` 而非 `src` 加载，若后续要做 tint 或无障碍 contentDescription 需要调整写法。

## 复盘与经验
- **通用弹窗控件要留扩展位**：`showSwitch`/`showInfo` 这类带默认值的具名参数是低成本扩展模式，新设计元素不再需要改每个调用方。
- **新增视觉元素的完整链条**：资源（png/vector）→ 布局控件 → 控制参数 → 调用方传参，四层缺一即"UI 不符"。
- **新参数默认值要选"旧行为不破坏"的方向**：此处默认 `showInfo=true` 恰好因为所有现存调用方都需要图标；反之则应默认 false。

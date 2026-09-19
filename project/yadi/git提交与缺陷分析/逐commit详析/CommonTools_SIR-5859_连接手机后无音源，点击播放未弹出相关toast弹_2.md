# SIR-5859 · 无音源 Toast 弹不出（自定义 Toast 布局 id 缺失）
- **提交**：`7468edd6` | 2026-08-18 | dufan | CommonTools | bugfix（配套 `5ec0a6f8` 的公共组件修复）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
SIR-5859 要求"无音源点击播放弹提示"，业务侧（`5ec0a6f8`）已调用 `ToastUtils.showCenterToast()`，但 Toast 实际弹不出来（宽度/对齐等设置不生效）。

## 根因分析
`component/CommonTools/.../utils/ToastUtils.kt` 的 Toast 构建代码里写的是 `toastView.findViewById<LinearLayout>(R.id.ll_toast)?.apply { gravity = ...; outMinWidth?.let { minimumWidth = ... } }`，而 `layout_custom_toast.xml` 的内层 `LinearLayout` 根本没有设置任何 id——`findViewById` 永远返回 null，`?.` 静默吞掉全部配置，Toast 视图以原始 wrap_content 状态显示异常（结合车机自定义 Toast 渲染链路即表现为弹不出/样式丢失）。业务层加了提示逻辑但公共组件渲染层失效，单靠应用提交无法达标，故有本配套修复。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt`、`component/CommonTools/src/main/res/layout/layout_custom_toast.xml`
```diff
--- component/CommonTools/src/main/java/com/yadea/common/utils/ToastUtils.kt
-        toastView.findViewById<LinearLayout>(R.id.ll_toast)?.apply {
+        toastView.findViewById<LinearLayout>(R.id.ll_inner)?.apply {
             gravity = outGravity
             outMinWidth?.let {
                 minimumWidth = context.resources.getDimensionPixelOffset(outMinWidth)
```
```diff
--- component/CommonTools/src/main/res/layout/layout_custom_toast.xml
     <LinearLayout
+        android:id="@+id/ll_inner"
         android:layout_width="wrap_content"
         android:layout_height="wrap_content"
         android:background="@drawable/bg_toast_round"
```

## 为什么能修复
给内层 `LinearLayout` 补上 `ll_inner` id 并让代码查找同名 id，`gravity`/`minimumWidth`（含业务传入的 `outMinWidth=580dp`）配置真正生效，自定义 Toast 按预期居中并达到最小宽度显示。属一处 id 对齐修复，无副作用；风险仅在于若其他布局文件已存在 `ll_toast` 被复用，需确认所有走 `showCenterToast` 的布局都改用 `ll_inner`。

## 复盘与经验
- `findViewById(...)?.apply {}` 的空安全写法会把"id 不存在"静默降级为"什么都没配"，公共组件里这类调用应加 require/assert 或至少 debug 日志，否则配置层 bug 完全不可见。
- 布局与代码的 id 契约变更（改名/新增 id）必须同提交同改两侧——本例正是"代码先写了不存在的 id"埋雷。
- 业务提交（5ec0a6f8）与公共组件提交（本提交）同日成对出现，说明联调/自测阶段就要跑通端到端 UI，组件级单测无法覆盖布局 id 绑定这类问题。

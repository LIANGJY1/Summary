# SIR-7405 · 无线网络列表刷新时"刷新"文言未隐藏，显示不正确
- **提交**：`c8ad24bd` | 2026-09-04 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
无线网络列表点击刷新时，刷新图标切换为旋转 loading 动画，但旁边的"刷新"文字控件仍显示，图文并排与 UI 设计不符。

## 根因分析
`WlanAdapter.convertTitleAvailable()` 中刷新交互只对 `iv_refresh` 图标做了处理（换 `ic_loading` 并用 `RotatingImageViewHelper.startRotation()` 旋转），刷新结束后也未复原；旁边 `tv_refresh` 文言控件完全没有参与状态切换——缺陷库"刷新时没有隐藏文言控件"与代码对应。根因是动画辅助类 `RotatingImageViewHelper` 只封装了单个 ImageView 的旋转状态，没有联动兄弟控件的能力，调用方也就无从隐藏文字。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/adapter/WlanAdapter.kt`、`application/Setting/src/main/java/com/yadea/setting/utils/RotatingImageViewHelper.java`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/adapter/WlanAdapter.kt
     private fun convertTitleAvailable(holder: BaseViewHolder, item: MultiAccessPoint) {
         mRotationHelper = RotatingImageViewHelper(
-            holder.getView(R.id.iv_refresh)
+            holder.getView(R.id.iv_refresh), holder.getView(R.id.tv_refresh)
         )
...
                 if (!it1) {
+                    holder.setGone(R.id.tv_refresh, true)
                         .getView<ImageView>(R.id.iv_refresh)
                         .setImageResource(R.drawable.ic_loading)
                     mRotationHelper?.startRotation()
```

```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/RotatingImageViewHelper.java
     private ImageView imageView;
+    private View tvView;
...
+    public RotatingImageViewHelper(ImageView imageView, View view) {
+        this(imageView);
+        this.tvView = view;
+    }
...
     public void startRotation() {   // 原方法体基础上新增
+        if (tvView != null) {
+            tvView.setVisibility(View.GONE);
+        }
...
     public void resetRotation() {   // 复原时
+        if (tvView != null) {
+            tvView.setVisibility(View.VISIBLE);
+        }
```

## 为什么能修复
`RotatingImageViewHelper` 新增可选的联动 View：`startRotation()` 时置 `GONE`、`resetRotation()` 时恢复 `VISIBLE`；调用方在启动刷新前先用 `holder.setGone(R.id.tv_refresh, true)` 立即隐藏，双保险保证整个刷新周期内文言不显示。构造重载保持旧单参构造兼容。隐患：helper 持有 ViewHolder 的 View 引用，若刷新动画期间列表项被回收，存在更新已回收 View 的理论风险（本场景刷新入口短平快，实际影响小）。

## 复盘与经验
- "图标 + 文字"组合控件做加载态切换时，要同时管理两个控件的状态；更好的做法是把组合封成一个自定义 refresh view，内部维护 idle/loading 两态，避免调用方分别操作。
- 工具类能力扩展时用重载构造 + 可空参数保持向后兼容（`tvView != null` 判断），不影响既有调用点。
- RecyclerView 中的动画辅助类要留意 ViewHolder 生命周期，动画回调里更新 View 前最好校验复用状态。

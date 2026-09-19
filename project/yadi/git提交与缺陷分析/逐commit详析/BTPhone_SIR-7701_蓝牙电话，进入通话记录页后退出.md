# SIR-7701 · 昼夜模式切换重建后通话详情页与通话记录页重叠
- **提交**：`8e698453` | 2026-09-07 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
进入通话记录详情页后退出应用，切换黑夜/白天模式再进入蓝牙电话，"最近通话"列表页与通话详情页同时显示、相互重叠。

## 根因分析
昼夜模式切换会触发 uiMode 配置变更，导致 `MainActivity` 被销毁重建。详情页的显隐是通过 `showFragmentLogDesc()` / `hideFragmentLogDesc()` 直接对 `callLogDescContainer`、`viewPager`、`layoutTabs` 三个 View 做 `setVisibility` 实现的，这套状态只存在于 View 树里，没有任何持久化。重建时布局回到 XML 默认态：详情容器不可见、viewPager 可见，但详情页的 Fragment（CallLogDescFragment）会随 FragmentManager 状态恢复而回来，结果详情 Fragment 与底层记录页同时可见——即"页面重建时状态异常，详情页和通话记录页一起显示"。修复采用标准的 onSaveInstanceState 持久化：新增 `STATE_CALL_LOG_DESC_VISIBLE` key，在 `onSaveInstanceState` 记录 `isCallLogDescVisible()`，`onCreate` 读回并调用统一的 `setCallLogDescVisible()` 恢复显隐；同时把三处显隐操作收敛进该方法，并在详情可见时额外隐藏 `recyclerViewMatches`/`searchNoMatch`（搜索残留视图，也是重叠来源之一）。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java（另有 progress_sync_style.xml 颜色微调、fragment_call_log_desc.xml 补背景色）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
@@ -83,6 +84,12 @@
         binding = ActivityMainBinding.inflate(getLayoutInflater());
         setContentView(binding.getRoot());
 
+        // 昼夜模式切换导致 Activity 重建时，恢复详情页与底层页面的显隐状态
+        boolean isCallLogDescVisible = savedInstanceState != null
+                && savedInstanceState.getBoolean(STATE_CALL_LOG_DESC_VISIBLE, false);
+        setCallLogDescVisible(isCallLogDescVisible);
@@ -913,9 +918,17 @@
     public void hideFragmentLogDesc() {
-        binding.callLogDescContainer.setVisibility(View.GONE);
-        binding.viewPager.setVisibility(View.VISIBLE);
-        binding.layoutTabs.setVisibility(View.VISIBLE);
+        setCallLogDescVisible(false);
+    }
+
+    private void setCallLogDescVisible(boolean visible) {
+        binding.callLogDescContainer.setVisibility(visible ? View.VISIBLE : View.GONE);
+        binding.viewPager.setVisibility(visible ? View.GONE : View.VISIBLE);
+        binding.layoutTabs.setVisibility(visible ? View.GONE : View.VISIBLE);
+        if (visible) {
+            binding.recyclerViewMatches.setVisibility(View.GONE);
+            binding.searchNoMatch.setVisibility(View.GONE);
+        }
+    }
+
+    @Override
+    protected void onSaveInstanceState(Bundle outState) {
+        outState.putBoolean(STATE_CALL_LOG_DESC_VISIBLE, isCallLogDescVisible());
+        super.onSaveInstanceState(outState);
     }
```

## 为什么能修复
显隐状态从"只活在 View 树"变成"随实例状态存取"，重建后 onCreate 立即按保存值恢复正确的页面层级，详情与列表互斥关系由单一方法保证，不会再出现两者同显。副作用：恢复时只处理了显隐，详情页数据由 Fragment 自身恢复；若在详情页停留期间进程被杀（非配置重建），savedInstanceState 仍在但 Fragment 数据为空，需依赖既有数据加载逻辑兜底。另外修复顺带把详情页底色 `bg_application` 补上，避免重叠时的视觉穿透。

## 复盘经验
- 任何"用 setVisibility 拼出来的页面状态"都必须考虑 Activity 重建：要么 onSaveInstanceState 持久化，要么用可恢复的导航组件（Fragment 事务 + backstack）。
- 显隐互斥逻辑分散在 show/hide 两个方法里容易漏分支，收敛到一个 setXxxVisible(visible) 单点，状态才不会漂移。
- 昼夜模式切换是车机上最常见的配置重建触发源，测试任何二级页面时都应包含"切日夜模式再进入"的用例。

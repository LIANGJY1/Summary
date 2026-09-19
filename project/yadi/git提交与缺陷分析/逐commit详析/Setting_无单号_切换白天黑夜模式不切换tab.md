# 无单号 · 切换白天黑夜模式不切换tab
- **提交**：`ebf679de` | 2026-08-25 | sgh | Setting | feature（diff 实为缺陷修复：日夜模式切换后 Activity 重建回到首页 tab）
- **关联单**：无

## 问题
切换白天/黑夜模式触发 uiMode 配置变更，MainActivity 重建后 `setPage(0)` 硬编码回到第一个 tab，用户停留在其它页面时被"踢回"首页，体验割裂。

## 根因分析
`onConfigurationChanged` 只刷新了导航适配器（notifyDataSetChanged），但 uiMode 变更同时导致 Activity 重建走 `initView`；`initView` 里无条件 `setPage(0)`，且没有任何机制记住重建前选中的 tab。设置类应用常驻车机、日夜切换随大灯自动触发，问题会高频复现。

## 关键代码修改
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
+    private var mCurrentPosition = 0
...
     override fun onCreate(savedInstanceState: Bundle?) {
         mIsReCreate = savedInstanceState != null
+        if (mIsReCreate) {
+            mCurrentPosition = savedInstanceState?.getInt(KEY_SELECTED_TAB, 0) ?: 0
+        }
         super.onCreate(savedInstanceState)
     }
...
-        setPage(0)
+        setPage(if (mIsReCreate) mCurrentPosition else 0)
...
+    override fun onSaveInstanceState(outState: Bundle) {
+        super.onSaveInstanceState(outState)
+        outState.putInt(KEY_SELECTED_TAB, mCurrentPosition)
+    }
```
`setPage` 内同步维护 `mCurrentPosition`（唯一写入点），`onSaveInstanceState` 持久化，`onCreate` 判断 `mIsReCreate` 恢复——标准的"实例状态保存/恢复"三段式，首次冷启动仍从 tab 0 进入。

## 为什么能修复
uiMode 变更 → 系统销毁重建 Activity 前 `onSaveInstanceState` 已把选中 tab 写入 Bundle → 重建后 onCreate 先于 super 读取该值 → initView 按记忆位置 setPage，重建前后用户所见页面一致；导航栏选中态由 setPage 内部刷新，无需额外处理。

## 复盘与经验
- Android 凡是会被配置变更重建的 Activity，任何"当前选中态"都应走 onSaveInstanceState 恢复，硬编码初始值是高频回归 bug。
- `mIsReCreate = savedInstanceState != null` 该仓库已有的标记在本修复中被充分利用，说明前人预留的状态标志在对的时候能省事。
- 局限：只恢复了 tab 位置，页面内部滚动位置、弹窗等仍未恢复；车机 uiMode 切换也可用 `android:configChanges` 直接免重建，但会失去资源自动刷新，需按项目夜间资源策略权衡。

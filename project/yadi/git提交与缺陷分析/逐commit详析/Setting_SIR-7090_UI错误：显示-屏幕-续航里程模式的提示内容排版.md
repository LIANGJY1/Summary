# SIR-7090 · 续航里程模式提示弹窗排版错误

- **提交**：`f252ed71` | 2026-09-02 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
显示-屏幕-续航里程模式的"问号"提示弹窗内容排版不符合 UI 设计，文字挤在一起、观感差。

## 根因分析
续航里程模式的提示此前直接复用通用的 `SentinelDialog`（title + content 两参数版本），其布局 `dialog_sentinel_small.xml` 是为短文案设计的内容区（`SentinelDialogSmall` 中还写死了 `mContentHeight = R.dimen.dp_276` 固定高度）。续航里程模式提示文案较长且带分段结构，塞进该通用单段文本容器后行距、对齐、间距全不匹配，形成"排版不对"。提交 what/how 标注为"自定 dialog"，即 UI 验收时认定该场景需要专用布局而非通用弹窗。

## 关键代码修改
改动文件：DisplayFragment.kt、DrivingFragment.kt、SentinelDialogSmall.kt、SentinelDialogTip.kt（新增）、dialog_sentinel_small.xml、dialog_sentinel_tip.xml（新增）、values(-en)/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
-                SentinelDialog(
-                    getString(R.string.extended_range_mode),
-                    getString(R.string.extended_range_mode_tip)
+                SentinelDialogTip(
+                    getString(R.string.extended_range_mode)
                 ).show(childFragmentManager, "ExtendedRangeModeTipDialog")
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/SentinelDialogTip.kt（新增）
+class SentinelDialogTip(
+    private val title: String = ""
+) : BaseDialogFragment() {
+    override fun onCreateView(...): View {
+        mContentWidth = ResourceUtils.getDimension(R.dimen.dp_660)
+        mContentHeight = LinearLayout.LayoutParams.WRAP_CONTENT
+        return mBinding.root   // 专用布局 dialog_sentinel_tip.xml（135 行，独立排版）
+    }
+}
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/SentinelDialogSmall.kt
-        mContentHeight = ResourceUtils.getDimension(R.dimen.dp_276)
+        mContentHeight = LinearLayout.LayoutParams.WRAP_CONTENT
+        tvContent.gravity = contentGravity   // 新增 contentGravity 参数，极限续航传 Gravity.START
```

## 为什么能修复
为新场景定制 `dialog_sentinel_tip.xml` 专用布局并由 `SentinelDialogTip` 承载，文字间距、分段与 UI 稿一一对应，消除通用弹窗硬塞长文案的排版错位；同时 `SentinelDialogSmall` 高度改为 `WRAP_CONTENT` 并支持自定义 gravity，长文案不再被 276dp 固定高度裁剪或居中错排。副作用小：只影响续航模式与极限续航两处提示弹窗，但通用弹窗能力被拆分后，后续同类提示需选择正确的 Dialog 类。

## 复盘与经验
- 通用"标题+正文"弹窗一旦遇到分段式/长文案场景，排版必然失控；UI 验收类缺陷高频根因是"复用错布局"而非代码逻辑错误。
- 弹窗内容区高度写死固定 dp（dp_276）是隐患，改 `WRAP_CONTENT` 是通用做法。
- 为可复用组件（SentinelDialogSmall）增加 gravity 等展示参数，比为每个场景复制一个新 Dialog 更可持续；但场景差异大时（如本例）独立布局更干净。

# YD-392850 · 慢充电流弹窗遮罩覆盖状态栏

- **提交**：`8cce282c` | 2026-07-08 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：未关联单号（提交带 YD-392850 单号，缺陷库 defs 为空）

## 问题
慢充电流信息弹窗弹出后，其背景遮罩盖住了屏幕顶部的状态栏，与 UI 规范不符。

## 根因分析
`SlowChargeInfoDialog` 原继承 `BaseDialog`（`Dialog` 封装），而 `BaseDialog` 在 onCreate 里设置了 `SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN` + `SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION`，使 Dialog 窗口布局延伸进状态栏/导航栏区域——全屏遮罩随之压过状态栏（提交消息自述）。这类"基类统一全屏沉浸"的设定对全屏页合适、对内容型弹窗就是灾难：**弹窗窗口的 flags 继承自基类，子类没有能力裁剪**。修复思路是弃用 Dialog 体系，改走 `DialogFragment`（`BaseDialogFragment`）：DialogFragment 由 Fragment 事务管理，内容视图与遮罩尺寸可独立控制（`setMContentWidth/Height`），不再吃基类的全屏 flags。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/custom/SlowChargeInfoDialog.java`（+44 重写）、`.../view/ui/MainActivity.java`（弹窗生命周期管理改造，含大量 lambda 缩进格式化）
```diff
// --- view/custom/SlowChargeInfoDialog.java：基类 BaseDialog → BaseDialogFragment
-public class SlowChargeInfoDialog extends BaseDialog {
-    public SlowChargeInfoDialog(@NonNull Context context, int theme) { super(context, theme); }
-    protected int getLayoutId() { return R.layout.dialog_slow_charge_info; }
+public class SlowChargeInfoDialog extends BaseDialogFragment {
+    private static final int DIALOG_WIDTH_DP = 660;
+    private static final int DIALOG_HEIGHT_DP = 344;
+    public View onCreateView(...) {
+        setMContentWidth(DIALOG_WIDTH_DP);
+        setMContentHeight(DIALOG_HEIGHT_DP);
+        return inflater.inflate(R.layout.dialog_slow_charge_info, container, false);
+    }
+    public void onViewCreated(...) {
+        setEnableClickMask(true);   // 遮罩点击关闭，替代原 setCanceledOnTouchOutside(true)
+        ...
+    }
```
```diff
// --- view/ui/MainActivity.java dismissSlowChargeInfoDialog()：Fragment 生命周期安全关闭
-        if (mSlowChargeInfoDialog.isShowing()) { mSlowChargeInfoDialog.dismiss(); }
+        if (mSlowChargeInfoDialog != null) {
+            if (mSlowChargeInfoDialog.isAdded()) {
+                mSlowChargeInfoDialog.dismissAllowingStateLoss();
+            }
+            mSlowChargeInfoDialog = null;
+        }
+        Fragment existing = getSupportFragmentManager().findFragmentByTag(SlowChargeInfoDialog.FRAGMENT_TAG);
+        if (existing instanceof SlowChargeInfoDialog) {
+            getSupportFragmentManager().beginTransaction().remove(existing).commitNowAllowingStateLoss();
```

## 为什么能修复
DialogFragment 走 Fragment 管理的对话框窗口，内容尺寸由 `setMContentWidth/Height` 定死为 660x344dp，遮罩不再继承 BaseDialog 的全屏沉浸 flags，状态栏得以露出；`setEnableClickMask(true)` 补齐遮罩点击关闭。同时关闭路径升级为 `dismissAllowingStateLoss()` + `findFragmentByTag` 清理残留实例，规避了 Activity 状态保存后 dismiss 的 `IllegalStateException` 与"僵尸弹窗"。隐患：尺寸常量硬编码 dp，不同分辨率车机上比例非严格一致；MainActivity 里 56 行改动混入了纯格式化，干扰 diff 审阅。

## 复盘与经验
- **基类的窗口级全局 flags 是隐式契约**：封装 Dialog 基类时设置的沉浸 flags 会传染所有子类，内容型弹窗要么走独立 DialogFragment 体系，要么基类提供 flags 开关。
- **DialogFragment 相比裸 Dialog 的两大红利**：生命周期安全（dismissAllowingStateLoss）+ 由 FragmentManager 防重复/清残留（findFragmentByTag），弹窗改造顺带消除了一类状态异常。
- 全屏遮罩类问题先查 window/systemUiVisibility flags，再查布局层级。

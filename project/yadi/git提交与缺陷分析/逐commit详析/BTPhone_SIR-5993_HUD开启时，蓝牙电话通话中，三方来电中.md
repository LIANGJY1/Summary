# SIR-5993 · HUD三方来电时来电号码与通话时间重叠

- **提交**：`313b053d` | 2026-08-19 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 HUD

## 问题
HUD 开启且蓝牙电话通话中又有三方来电时，HUD 界面上的来电号码文本过长，与下方显示的通话时间文字重叠。

## 根因分析
`float_hud_window.xml` 根布局是 `wrap_content` 尺寸，来电号码 TextView `tv_user` 宽度也是 `wrap_content`，仅靠 `android:maxEms="11"` 限宽——超长号码（三方来电场景常无联系人名只显号码）仍可超出约束挤压/覆盖垂直排列的 `tv_time`。缺陷库"显示宽度未限制"即指此。修复按 UI 原型把 HUD 窗口固定为 `@dimen/dp_148 x @dimen/dp_144`，`tv_user`/`tv_tips` 改为 `match_parent` 宽度并配 `singleLine`/`maxLines=1` + `ellipsize="marquee"`，删除不可靠的 `maxEms`；配套把 `FloatWindowManager` 挂载 HUD 时的 padding 从左右各 48dp 改为左 0 右 6dp（注释明确"通过 View 的 padding 实现右边距比 LayoutParams.x 更可靠"），给内容让出横向空间，避免限宽后文字被 padding 挤没。本提交还捎带了大量联系人"协议库缓存恢复"逻辑（`restoreContactsFromProtocolCacheOnce`、`mContactsDownloadState` 等），与该 HUD 缺陷无直接关系，属于同一时期的连带改动。

## 关键代码修改
改动文件：float_hud_window.xml、FloatWindowManager.java、ContactsRepository.java、InCallServiceImpl.java、InCallUiStateMachine.java、ContactsViewModel.java（6 文件，+250/-40）
```diff
@@ application/BTPhone/src/main/res/layout/float_hud_window.xml @@
-    android:layout_width="wrap_content"
-    android:layout_height="wrap_content"
+    android:layout_width="@dimen/dp_148"
+    android:layout_height="@dimen/dp_144"
...
         android:id="@+id/tv_user"
-        android:layout_width="wrap_content"
+        android:layout_width="match_parent"
         android:layout_height="wrap_content"
         android:textColor="#FEFEFE"
-        android:textSize="18sp"
+        android:textSize="24sp"
         android:singleLine="true"
-        android:layout_marginTop="10dp"
-        android:maxEms="11"
+        android:layout_marginTop="@dimen/dp_7"
+        android:ellipsize="marquee"
```
```diff
@@ application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatWindowManager.java @@
-        int rightMarginPx = dpToPx(48);
-        int lefttMarginPx = dpToPx(48);
-        hudView.setPadding(lefttMarginPx, hudView.getPaddingTop(),
+        int rightMarginPx = dpToPx(6);
+        hudView.setPadding(0, hudView.getPaddingTop(),
                 rightMarginPx, hudView.getPaddingBottom());
```

## 为什么能修复
固定尺寸 + `match_parent` + 跑马灯省略形成完整的宽度约束链：号码再长也只在 `tv_user` 自身区域内滚动，不会侵入 `tv_time` 的布局空间，重叠消除；字号从 18sp 提到 24sp 与 UI 原型对齐。隐患：`FloatWindowManager` 的 padding 大改会影响所有 HUD 内容的横向定位，需回归验证其它 HUD 场景（如去电/通话中界面）没有因此贴边或截断。

## 复盘与经验
- 弹性尺寸（wrap_content）+ 软限制（maxEms）扛不住不可控的外部数据（来电号码），悬浮窗这类异形 UI 应按原型定死尺寸再做内部约束。
- `maxEms` 限的是字符宽度估算而非像素约束，无法防止 TextView 相互覆盖；防重叠要靠布局约束（match_parent + constraint 链）而非字符数。
- 悬浮窗 padding 是全局生效的，调整时要想清楚影响的是所有 HUD 视图。

# SIR-6615 · 登录页《账号中心》《隐私政策》文字过小与 UI 不一致

- **提交**：`f5bb74f5` | 2026-08-26 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心

## 问题
登录页底部"《用户协议》《隐私政策》。"一行文字字号过小，与 UI 设计稿不一致；同页登录按钮等元素的尺寸/间距也与稿有偏差。

## 根因分析
`application/AccountCenter/src/main/res/layout/activity_login.xml` 中协议行四个 `TextView`（`tv_user_agreement`、逗号、`tv_privacy_policy`、句号）的 `textSize` 均为 `14sp`，UI 标注为 `24sp`——与 SIR-6601（扫码弹窗 14/16sp→24sp）同批次同源：初版布局按手机端习惯字号估写，UI 定稿后未按车机标注走查修正。本提交顺带整页对齐了其他偏差（按钮 200x48dp→279x74dp、字号 18sp→28sp、间距调整），并新增应用图标资源 `app_icon2.png` 替换原 `bg_rounded` 背景。

## 关键代码修改
改动文件：`application/AccountCenter/src/main/res/layout/activity_login.xml`、`application/AccountCenter/src/main/res/drawable/app_icon2.png`（新增图片资源）

```diff
--- a/application/AccountCenter/src/main/res/layout/activity_login.xml
@@ -36,44 +35,45 @@
             <TextView
                 android:id="@+id/tv_user_agreement"
                 android:layout_width="wrap_content"
                 android:layout_height="wrap_content"
                 android:text="@string/user_agreement"
                 android:textColor="#007AFF"
-                android:textSize="14sp"/>
+                android:textSize="24sp"/>
             <TextView
                 android:text="@string/comma"
                 android:textColor="#000000"
-                android:textSize="14sp"/>
+                android:textSize="24sp"/>
             <TextView
                 android:id="@+id/tv_privacy_policy"
                 android:text="@string/privacy_policy"
                 android:textColor="#007AFF"
-                android:textSize="14sp"/>
+                android:textSize="24sp"/>
             <TextView
                 android:text="@string/period"
                 android:textColor="#000000"
-                android:textSize="14sp"/>
+                android:textSize="24sp"/>
         </LinearLayout>
         <!-- 登录按钮 -->
         <Button
             android:id="@+id/btn_login"
-            android:layout_width="200dp"
-            android:layout_height="48dp"
+            android:layout_width="279dp"
+            android:layout_height="74dp"
             android:background="@drawable/rounded_button_background"
-            android:textSize="18sp"/>
+            android:textSize="28sp"/>
```

## 为什么能修复
协议行文字统一提升到 UI 标注的 `24sp`，登录按钮尺寸/字号同步对齐，整页还原度达标。纯布局样式改动，无逻辑风险。注意点：同页可点击的 `tv_user_agreement`/`tv_privacy_policy` 字号变大后 `wrap_content` 行宽增加，需确认未与图标/按钮产生重叠；本单与 SIR-6601 同类同批出现，说明该应用的"字号按 UI 标注核对"环节整体缺失。

## 复盘与经验
- 同一批 UI 还原缺陷（6601、6615、6618 颜色）集中在账号中心，提示新模块提测前应有一轮"设计稿逐页对照走查"，能一次性拦截整类字号/颜色/间距问题。
- 一行内成组的多个 TextView（文字+标点）字号必须一致，散写 `textSize` 是拆分标点实现方式的固有风险，建议整行用带 `<xliff>` 占位或 Spannable 的单控件实现。
- 修复时顺手把同页其他可见偏差一起对齐（本 commit 做法值得肯定），避免同一页面被 QA 反复提单。

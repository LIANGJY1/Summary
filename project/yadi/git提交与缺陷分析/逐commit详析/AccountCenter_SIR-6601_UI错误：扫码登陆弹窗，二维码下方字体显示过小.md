# SIR-6601 · 扫码登录弹窗二维码下方文字过小与 UI 不一致

- **提交**：`3c81642b` | 2026-08-26 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心

## 问题
扫码登录弹窗中二维码下方的提示文字（`qrcode_tips`、`qrcode_desc`、《用户协议》《隐私政策》链接）字号明显小于 UI 设计稿。

## 根因分析
`application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml` 中四处 `TextView` 的 `textSize` 分别写成了 `16sp` 与 `14sp`，而 UI 标注为 `24sp`。这是典型的"布局初版字号随手估写、未按 UI 标注走查"问题——车机大屏的观看距离远，移动端习惯的小字号在车机上不适用，视觉上文字明显偏小。

## 关键代码修改
改动文件：`application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml`

```diff
--- a/application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml
@@ -32,7 +32,7 @@
             android:text="@string/qrcode_tips"
-            android:textSize="16sp"
+            android:textSize="24sp"
@@ -45,7 +45,7 @@
                 android:text="@string/qrcode_desc"
-                android:textSize="14sp"
+                android:textSize="24sp"
@@ -53,7 +53,7 @@
                 android:text="@string/user_agreement"
-                android:textSize="14sp"
+                android:textSize="24sp"
@@ -61,7 +61,7 @@
                 android:text="@string/privacy_policy"
-                android:textSize="14sp"
+                android:textSize="24sp"
```

## 为什么能修复
四个 `TextView` 字号统一提升到 UI 标注的 `24sp`，弹窗文字视觉尺寸与设计稿一致。纯样式改动，无逻辑风险；文字变大后单行 `wrap_content` 宽度增加，若接近弹窗边界需关注换行，实测该弹窗空间充足未见问题。可改进点：同弹窗 4 处字号统一走 dimen/样式资源（如 `textSize="@dimen/sp_24"` 或共同 style），避免下次改 UI 标注时再逐处搜代码。

## 复盘与经验
- 车机 HMI 的字号规范普遍比手机端大一档（本例 14/16sp → 24sp），从移动端迁移布局时字号必须按车机 UI 标注重新确认，凭经验估写必错。
- 同一弹窗内成组的文字（提示 + 链接）字号应统一引用同一 dimen/style，改起来一处生效；本 commit 四处散写就是反例。
- UI 还原类缺陷（字号、颜色、间距）最好在提测前用设计稿对照清单做一次静态走查，比等 QA 提单成本低得多。

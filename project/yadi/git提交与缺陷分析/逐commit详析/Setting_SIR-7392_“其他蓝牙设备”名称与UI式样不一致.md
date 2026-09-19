# SIR-7392 · "其他蓝牙设备"名称与 UI 式样不一致
- **提交**：`ec66f0d0` | 2026-09-04 | dufan | Setting | bugfix（文言修改）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
蓝牙弹窗中的入口文案显示"其他蓝牙设备"，与 UI 设计式样要求的"耳机蓝牙"不一致。

## 根因分析
纯文言问题：`dialog_bluetooth.xml` 中入口 TextView 引用 `@string/other_bluetooth`（值为"其他蓝牙设备"），设计稿定稿后该 Tab 语义收窄为耳机蓝牙，文案资源未跟进；且中英文资源（`values/strings.xml` 与 `values-en/strings.xml`）需同步修改，避免英文环境漏改。

## 关键代码修改
改动文件：`application/Setting/src/main/res/layout/dialog_bluetooth.xml`、`application/Setting/src/main/res/values/strings.xml`、`application/Setting/src/main/res/values-en/strings.xml`

```diff
--- application/Setting/src/main/res/layout/dialog_bluetooth.xml
                 android:drawableBottom="@drawable/selector_bluetooth_bottom"
                 android:drawablePadding="@dimen/dp_12"
-                android:text="@string/other_bluetooth"
+                android:text="@string/earphones_bluetooth"
```

```diff
--- application/Setting/src/main/res/values/strings.xml
-    <string name="other_bluetooth">其他蓝牙设备</string>
+    <string name="earphones_bluetooth">耳机蓝牙</string>
```

## 为什么能修复
布局改引用新字符串键 `earphones_bluetooth`，中英两份资源同步更新为"耳机蓝牙"（英文同步替换），与设计式样一致。无逻辑风险；注意旧键 `other_bluetooth` 若无其他引用可清理，避免残留双键造成后续误用。本提交为 cherry-pick（源自 `ac38567f`），属分支间同步修复。

## 复盘与经验
- 需求式样变更后的文案类缺陷，修改时要同时覆盖全部 locale 资源目录，漏改 `values-en` 会出现"中文对了英文不对"的次生缺陷。
- 文案变更若伴随语义收窄（"其他蓝牙设备"→"耳机蓝牙"），字符串键名也应随之更名（`other_bluetooth` → `earphones_bluetooth`）而非只改 value，让键名自解释。

# SIR-7088 · 编辑本机名称弹窗空内容提示词不符

- **提交**：`0f84ced1` | 2026-09-02 | sgh | Setting | bugfix（纯文案修正）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
连接-编辑本机名称的二次弹窗中，输入框为空时的占位提示文案与 UI 稿不符。

## 根因与修复说明
缺陷库根因"多余字符"、方案"删掉多余字符"。改动仅 `values/strings.xml` 一行：

```diff
// application/Setting/src/main/res/values/strings.xml
-    <string name="set_device_name_hint">请输入本机名称</string>
+    <string name="set_device_name_hint">请输入名称</string>
```

即 `set_device_name_hint` 资源文案按 UI 稿去掉了多余的"本机"二字；引用该资源的编辑弹窗（`CustomEditDialogFragment` 一系）hint 随之恢复设计稿样式。未同步 `values-en` 属同类隐患（英文 hint 需另行核对）。

## 为什么能修复
hint 文案由 string 资源统一驱动，改资源即全量生效，零逻辑风险。

## 复盘与经验
- 文案类 UI 缺陷修复成本极低，但暴露的是"文案没有过 UI 稿评审"的流程缺口，交付前对照设计稿逐字校对可避免。
- 文案资源集中管理（string token）的价值就在于此：一处修改、所有引用点生效。

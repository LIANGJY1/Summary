# SIR-7086 · 连接-编辑本机名称的二次弹窗命名不对
- **提交**：`6c5ba024` | 2026-09-02 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
连接设置页"编辑"本机名称弹出的二次弹窗标题为"修改本机名称"（英文 "Edit Device Name"），与设计稿要求的"本机名称"（"Device Name"）不符。

## 根因分析
字符串资源 `set_device_name` 录入文案与设计稿不一致：中英文两份 strings.xml 中均多出动作语义前缀（"修改"/"Edit"）。设计稿的弹窗标题是名词性"本机名称"，动作语义应由底部"确认/取消"按钮承担。

## 关键代码修改
改动文件：`application/Setting/src/main/res/values/strings.xml`、`res/values-en/strings.xml`
```diff
--- application/Setting/src/main/res/values/strings.xml
-    <string name="set_device_name">修改本机名称</string>
+    <string name="set_device_name">本机名称</string>

--- application/Setting/src/main/res/values-en/strings.xml
-    <string name="set_device_name">Edit Device Name</string>
+    <string name="set_device_name">Device Name</string>
```

## 为什么能修复
标题文案改为设计稿的名词短语，中英文同步修改保证多语言一致。单 key 双文件修改，零逻辑风险。

## 复盘与经验
- 修改中英文文案时必须两个资源文件同步（本提交做对了），否则语言切换后同一弹窗标题语义不一致。
- 弹窗标题应是名词性短语，动作语义放按钮——文案评审时建立该规则可避免"修改本机名称"这类歧义标题。
- 与 `d6ce5ec8`（错别字）、`167b86a8`（文案转义）一样，文言类缺陷占了本批次 C 级单的相当比例，值得建立文案校对清单前置拦截。

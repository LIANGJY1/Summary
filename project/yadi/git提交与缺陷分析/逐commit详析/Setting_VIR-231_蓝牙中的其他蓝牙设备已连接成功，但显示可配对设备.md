# VIR-231 · 蓝牙"其他设备已连接成功"却显示"可配对设备"文案

- **提交**：`4c0be67c` | 2026-08-06 | dufan | Setting | bugfix（文案修正类）
- **缺陷库**：未关联单号（单号 VIR-231 仅出现在提交标题，defs 为空）

## 问题
蓝牙设置页里设备已配对/连接成功，但分组标题文案仍显示"可配对设备"，让用户误以为设备未连接。

## 根因分析
纯文案错误：字符串资源 `paired_device` 中文值被写成"已可配对设备(%1$s)"，"已"+"可配对"语义冲突，用户读起来就是"可配对设备"；英文值为"Paired Device"但缺少设备计数占位符 `%1$s`，与代码中 `getString(R.string.paired_device, count)` 的格式化用法不匹配（英文下会显示原始占位符或被丢弃）。`[why]文言变更`表明 UI 稿文案修订后未同步到资源文件。逻辑代码未动，只改 `values/strings.xml` 与 `values-en/strings.xml` 两处资源。

## 关键代码修改
改动文件：`application/Setting/src/main/res/values-en/strings.xml`、`application/Setting/src/main/res/values/strings.xml`
```diff
// application/Setting/src/main/res/values/strings.xml
-    <string name="paired_device">已可配对设备(%1$s)</string>
+    <string name="paired_device">已配对设备(%1$s)</string>
// application/Setting/src/main/res/values-en/strings.xml
-    <string name="paired_device">Paired Device</string>
+    <string name="paired_device">Paired Device(%1$s)</string>
```

## 为什么能修复
中文文案改为语义准确的"已配对设备(%1$s)"，与连接状态一致；英文补上 `%1$s` 占位符后与中文版本格式化参数数量一致，避免 `getString(res, args)` 因缺占位符导致的显示异常。副作用为零，但注意资源文件里相邻的 `available_devices_count` 仍是中文"可配对设备(%1$s)"放在默认 values 目录（英文目录同样），属于遗留的多语言卫生问题。

## 复盘与经验
- 中英文 string 资源改动必须成对检查，尤其是 `%1$s` 这类格式化占位符，缺一个在英文环境就会显示异常。
- "已可配对设备"这类一字之差的文案歧义，测试看截图很难发现，文案评审要对照交互稿逐字核对。
- 默认 values 目录中出现硬编码中文（如 `available_devices_count`），说明多语言规范执行不严，建议 lint 加英文目录文案完整性检查。

# SIR-7395 · 我的网络中，WiFi未连接状态与UI不符
- **提交**：`ff61655c` | 2026-09-04 | dufan | HardwareLibs | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
"我的网络"页面中，已保存但未连接的 WiFi 热点状态文案显示"已保存"，与 UI 设计稿要求的"未连接"不符。

## 根因分析
纯文案（文言）问题。`component/Hardwarelibs/src/main/res/values/strings.xml` 中复用了 AOSP 标准字符串 `wifi_remembered`，其默认中文翻译为"已保存"——这是 Android 原生 WiFi 设置里对"已保存网络"的表述。但本项目的 UI 式样书规定该状态显示"未连接"，代码逻辑（状态判定）本身没有错，只是资源文案与设计稿不一致。

## 关键代码修改
改动文件：component/Hardwarelibs/src/main/res/values/strings.xml
```diff
--- component/Hardwarelibs/src/main/res/values/strings.xml
-    <string msgid="3266709779723179188" name="wifi_remembered">"已保存"</string>
+    <string msgid="3266709779723179188" name="wifi_remembered">"未连接"</string>
```

## 为什么能修复
直接把 `wifi_remembered` 的中文值改为"未连接"，所有引用该资源的状态行（含"我的网络"列表）随之更新。无逻辑改动、无副作用；小隐患是这属于对 AOSP 标准资源的定制改写，若未来升级 AOSP 版本或引入依赖同名字符串的三方库，语义可能与"已保存"的通用理解出现偏差。

## 复盘与经验
- "UI 与式样不符"类缺陷先查 strings.xml 等资源层，多数是复用系统默认文案未按设计稿定制，改动成本最低但也要过一遍所有引用点防止误伤。
- 复用 AOSP 标准字符串时要有"默认翻译≠产品文案"的意识，式样书评审阶段就应核对关键状态字段的文言。

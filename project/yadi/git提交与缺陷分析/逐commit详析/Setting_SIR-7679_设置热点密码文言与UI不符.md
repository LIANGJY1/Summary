# SIR-7679 · 设置热点密码弹窗文言与UI不符
- **提交**：`2fc26654` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置热点密码弹窗的标题/文言显示为"设置热点密码"，与 UI 设计稿要求的"热点密码"不一致。

## 根因分析
问题出在资源定义层而非引用层：`application/Setting/src/main/res/values/strings.xml` 中 `set_hotspot_password` 这条字符串资源的值本身写成了"设置热点密码"，而 UI 稿定稿为"热点密码"。与同文件的 `hotspot_password`（值也是"热点密码"）形成了语义重复的两条资源，说明初版 UI 文案与最终定稿存在版本偏差，代码未随设计稿更新。纯文案资源值错误，无任何逻辑代码改动。

## 关键代码修改
改动文件：application/Setting/src/main/res/values/strings.xml
```diff
--- application/Setting/src/main/res/values/strings.xml
@@ -131,7 +131,7 @@
     <string name="hotspot_name">热点名称</string>
     <string name="hotspot_password">热点密码</string>
     <string name="frequency_band">热点频段</string>
-    <string name="set_hotspot_password">设置热点密码</string>
+    <string name="set_hotspot_password">热点密码</string>
     <string name="set_hotspot_name">设置热点名称</string>
     <string name="set_hotspot_name_hint">请输入新的名称</string>
     <string name="current_hotspot_password">当前密码：</string>
```

## 为什么能修复
直接把 `set_hotspot_password` 资源值改为 UI 定稿的"热点密码"，所有引用该资源的界面一次性对齐，无需触碰逻辑代码。副作用：若其他界面共用此资源且各自 UI 稿文案不同，会连带变化——本例中该资源仅热点密码弹窗使用，风险可控；`set_hotspot_name`（"设置热点名称"）仍保留旧文案，暗示两处文案可能需要分别核对。

## 复盘与经验
- 文案不符不一定改引用处，也可能要改 strings.xml 资源值本身；先分清"资源值错"还是"引用错资源"。
- 同义资源并存（`hotspot_password` 与 `set_hotspot_password` 值相同）是文案演进的历史包袱，建议定期合并去重，避免改一处漏一处。
- UI 定稿变更后应有文案 diff 清单驱动代码更新，而不是依赖测试走查兜底。

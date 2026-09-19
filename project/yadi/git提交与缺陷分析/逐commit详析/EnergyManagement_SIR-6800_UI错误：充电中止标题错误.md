# SIR-6800 · 充电中止标题文案错误
- **提交**：`36d78141` | 2026-08-28 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
能量中心充电流程中止时，弹窗/状态标题显示为"充电终止"，与 UI 稿要求的"充电中止"不一致。

## 根因分析
开发时文案按直觉写成"充电终止"，未对照 UI 切图标注。字符串集中在 `application/EnergyManagement/src/main/res/values/strings.xml` 中定义，`charge_state_stopped` 这一条与设计稿文字（"充电中止"）差一个字。属于典型的文案类 B 级缺陷：功能逻辑正确，仅字面错误。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/values/strings.xml
```diff
--- a/application/EnergyManagement/src/main/res/values/strings.xml
@@ -82,7 +82,7 @@
     <string name="charge_state_charging" translatable="false">充电中</string>
     <string name="charge_state_abnormal" translatable="false">充电异常</string>
     <string name="stop_charging" translatable="false">停止充电</string>
-    <string name="charge_state_stopped" translatable="false">充电终止</string>
+    <string name="charge_state_stopped" translatable="false">充电中止</string>
     <string name="charge_state_completed" translatable="false">充电完成</string>
```

## 为什么能修复
资源字符串是标题显示的唯一来源，改一处资源即可全局生效，无任何逻辑副作用。隐患仅在于：若代码中有依赖字符串内容做判断（而非引用 R.string）的写法会失配，本提交场景不存在该问题。

## 复盘与经验
- 文案类缺陷应对照 UI 标注逐字校对，"终止/中止"这类近义词最易混入。
- 字符串统一放 strings.xml 且 `translatable="false"`，保证一处修改全局生效，是这次能一行修完的前提。
- 缺陷库域名标为"3D车模"但实际改动在 EnergyManagement 模块，说明缺陷录入时的域划分与代码模块并不严格对应，复盘时应以 diff 实际为准。

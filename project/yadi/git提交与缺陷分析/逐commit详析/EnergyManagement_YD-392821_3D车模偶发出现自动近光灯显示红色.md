# YD-392821 · 3D 车模偶发自动近光灯显示红色

- **提交**：`b2ec79fc` | 2026-07-07 | liqingqing | EnergyManagement(Launcher/3D车模) | bugfix
- **缺陷库**：未关联单号（提交带 YD-392821 单号，缺陷库 defs 为空）

## 问题
3D 车模偶发出现"自动近光灯"显示红色（异常态）。注意：本单号 YD-392821 与 ce73174e（充电异常多一段文言）复用，缺陷库 defs 为空。

## 根因分析
`KanziSignalMapping.java` 中远光灯信号 handler 把两个毫不相干的 kanzi 属性绑在了一起：收到远光状态后，除了写入 `KanziType.Light.FAR_BEAM`，还把**同一个数值**写入 `KanziType.CarModel.SMARTLIGHT_STATE`（智能远光灯状态）。提交消息自述："智能远光灯和远光灯关联了，读到远光灯信号变化时还给智能远光灯下发了信号"。SMARTLIGHT_STATE 有自己的取值语义（如 2 表示故障/红色），被远光值（0/1）越权写入后，3D 模型的智能远光灯进入异常配色。此外灯光超时复位分支（`mLightTimeoutFlag == 1` 时批量清零）也顺带把 SMARTLIGHT_STATE 写成 2，同样属于越界联动。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java`（+3/-3，均为注释掉越界写入）
```diff
// --- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java 远光灯 handler
             kanziManager.setValue("", KanziType.Light.FAR_BEAM, highBeam);
-            LogUtils.d(TAG, "Smart light state -> Kanzi: SmartLight_State=" + highBeam);
-            kanziManager.setValue("", KanziType.CarModel.SMARTLIGHT_STATE, highBeam);
+//            kanziManager.setValue("", KanziType.CarModel.SMARTLIGHT_STATE, highBeam);
// --- 灯光超时复位分支
                 kanziManager.setValue("", KanziType.Light.FAR_BEAM, 0);
-                kanziManager.setValue("", KanziType.CarModel.SMARTLIGHT_STATE, 2);
+                //kanziManager.setValue("", KanziType.CarModel.SMARTLIGHT_STATE, 2);
```

## 为什么能修复
取消"远光信号→SMARTLIGHT_STATE"的越界写入后，智能远光灯状态只由其专属信号驱动，不再被远光值/超时复位篡改，红色异常显示消失。隐患：被注释的两行以注释而非删除方式保留，且若产品后续确有"远光联动智能远光"的诉求，需要重新设计映射而非复用旧写法；超时复位不再动 SMARTLIGHT_STATE，若智能远光自身无超时兜底，其状态恢复依赖原信号链路。

## 复盘与经验
- **一个信号 handler 只写自己语义域的属性**：复制粘贴 signal handler 时把"顺手再写一个"留了下来，是车机信号映射代码的高发缺陷；SMARTLIGHT_STATE 的取值域与 FAR_BEAM 不同，混写必然在某个组合下露馅。
- **"偶发"往往是信号组合时序所致**：平时远光与智能远光值恰好相容，特定序列（如超时复位+远光变化）才出现错值—— review 信号映射时要看取值域是否兼容，而非仅看单条路径。
- 三个不同 bug 挂同一 YD 单号（本提交与 ce73174e），单据复用会影响追溯，流程上应避免。

# SIR-8124 · 模拟触发P档时3D车模尾箱更改按钮消失

- **提交**：`8e793ae2` | 2026-09-16 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模场景模拟触发 P 档（伴随里程信号超时标志置位）后，尾箱"更改"按钮从车模上消失。

## 根因分析
`KanziSignalMapping` 中尾箱状态原本由两条路径维护：里程超时 `mMileageTimeoutFlag == 1` 时走 `updateRearBoxOnlineStatus()`，无条件向 Kanzi 发送 `KanziType.CarModel.ECO_TRUNK_STATE = 3`（故障态）；正常路径 `updateRearBoxStatusToKanzi()` 才按 `mRearBoxStatus`（1=已安装，0=未安装）发 1/2/0。问题在于：Kanzi 3D 车模的故障态素材只有"已安装尾箱"的版本，**未安装的尾箱没有故障态**——对未安装尾箱发 3，Kanzi 侧状态机无法落位，表现就是尾箱按钮消失。P 档模拟恰好把超时标志置 1，于是未安装配置下必现。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java
@@ 新增字段
     // 后尾箱安装状态
     private int mRearBoxStatus = 0;
+    // 最近一次有效安装状态：-1=尚未收到，0=未安装，1=已安装；故障/超时不覆盖此缓存
+    private int mLastValidRearBoxStatus = -1;
@@ THREE_D_MODEL_PCU_REARBOXSTATUS 处理器
             mRearBoxStatus = (int) event.getValue();
+            if (mRearBoxStatus == 0 || mRearBoxStatus == 1) {
+                mLastValidRearBoxStatus = mRearBoxStatus;
+            }
@@ 删除 updateRearBoxOnlineStatus()：超时不再无条件发 3
-    private void updateRearBoxOnlineStatus() {
-        if (mMileageTimeoutFlag == 1) {
-            sendToKanzi(KanziType.CarModel.ECO_TRUNK_STATE, 3);
-        } else {
-            updateRearBoxStatusToKanzi();
-        }
-    }
@@ updateRearBoxStatusToKanzi 统一收敛
-        if (mRearBoxStatus == 1) {
+        if (mMileageTimeoutFlag == 1 || mRearBoxStatus == 3) {
+            // 未安装后信号丢失时保持隐藏；连续故障仍依据最后一次有效安装状态判断。
+            int rearBoxState = (mLastValidRearBoxStatus == 0) ? 0 : 3;
+            sendToKanzi(KanziType.CarModel.ECO_TRUNK_STATE, rearBoxState);
+        } else if (mRearBoxStatus == 1) {
             sendToKanzi(KanziType.CarModel.ECO_TRUNK_STATE, (mRearBoxCoverStatus == 1) ? 2 : 1);
```

## 为什么能修复
核心是引入"最近一次有效安装态"缓存 `mLastValidRearBoxStatus`：只有明确的 0/1 信号才更新它，超时/故障值不污染缓存。超时或 `mRearBoxStatus==3` 时按缓存决策——未安装发 0（保持未安装态，按钮按未安装规则显示/隐藏一致），已安装才发 3（故障态素材存在，渲染正常）。超时与故障两条异常路径收敛进同一个 `updateRearBoxStatusToKanzi()`，消除了原来"超时特判"与"正常映射"两套逻辑对同一信号的撕裂。隐患：信号首次到达前缓存为 -1，此时若直接超时，会按"非未安装"发 3，仍依赖后续真实信号纠偏。

## 复盘与经验
- 与渲染端（Kanzi）对接的枚举值必须在双方状态表里逐一存在：给"未安装"发"故障"这类越界值，渲染端往往静默失效，排查时先对齐状态机再怀疑通信。
- 超时/故障信号不可直接当状态用——维护"最后一次有效值"缓存是处理信号丢失的标准模式，且缓存只接受有效值更新。
- 同一信号的多个写入点（超时特判 + 正常映射）迟早产生不一致，尽快收敛到单一出口函数。

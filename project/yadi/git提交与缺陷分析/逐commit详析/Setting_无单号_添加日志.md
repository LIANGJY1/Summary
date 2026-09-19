# 无单号 · [SIR-XXX] 添加日志
- **提交**：`a61834e8` | 2026-09-10 | dufan | Setting | feature（实为排查辅助日志）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
在蓝牙配对分支中补一行日志，用于排查配对流程问题（无功能变更）。

## 实现结构
仅改动 `utils/BluetoothUtil.kt` 1 行：在配对分支的 else 路径（非绑定列表内的设备直接发起配对）调用 `startPairing()` 前增加一条 `log("startPairing:${it}")`，输出即将发起配对的目标设备对象。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
} else {
+   log("startPairing:${it}")
    it.startPairing()
}
```

实现讲解：单行日志，打在 `startPairing()` 调用点之前，把设备对象直接内插输出。选择在"直接配对"这一分支加日志，说明当时排查的正是该分支设备配对不生效/无响应类问题。

## 复盘与要点
- **类型标注**：标题标 feature，实为排查辅助日志，无功能语义。
- **可复用手法**：在副作用调用（startPairing/连接/写属性）前打印入参，是蓝牙问题定位性价比最高的埋点位。
- **风险**：直接内插 `it` 依赖对象 `toString()`，若未重写则只输出对象地址，定位价值有限；建议打印设备名/MAC 等关键字段。

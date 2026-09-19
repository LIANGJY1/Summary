# SIR-4340 · 控制中心蓝牙开启未连接状态重新上下电后无图标显示

- **提交**：`18841f3b` | 2026-07-28 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
蓝牙处于"开启未连接"状态时整车重新上下电，控制中心（负一屏）蓝牙模块的图标不显示。

## 根因分析
`BasicServicesTile.updateBluetooth()` 负责根据蓝牙开关/连接状态刷新图标。蓝牙图标有两个控件 `bluetooth1Image` / `bluetooth2Image`，`updateBluetoothIcon(connectState)` 中按设备类型会置 `bluetooth1Image.setVisibility(View.GONE)`（如 HFP 耳机时改用 bluetooth2Image）。上下电后回调 `updateBluetooth()` 走到 `openState=false` 分支（上下电过程中状态回调时序不确定，蓝牙状态先以"关"或初始态刷新）时，该分支只调用了 `bluetooth1Image.setImageResource(R.drawable.vector_bt)` 换图，却**没有恢复可见性**——若上一次状态残留把 `bluetooth1Image` 置成了 GONE，图标就永远不出现。缺陷库根因"控件未设置可见性"与此一致。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BasicServicesTile.java

```diff
--- application/SystemUI/src/main/java/com/android/systemui/dropdownbar/quicksetting/ui/BasicServicesTile.java
         } else {
             tvBluetoothName.setText("");
             bluetooth1Image.setImageResource(R.drawable.vector_bt);
+            bluetooth1Image.setVisibility(View.VISIBLE);
             bluetooth2Image.setVisibility(View.GONE);
         }
```

## 为什么能修复
在写入通用蓝牙图标 `vector_bt` 的分支补上 `setVisibility(View.VISIBLE)`，使"换图"与"恢复可见性"两个操作绑定在一起，无论上一状态残留的是 GONE 还是 VISIBLE，该分支刷新后图标必然可见。副作用极小：`bluetooth2Image` 同步保持 GONE，与"未连接显示通用图标"的语义一致。顺带一提，同文件 `updateBluetoothIcon` 中 `bluetooth1Image.setImageResource(View.GONE)`（把 View.GONE 当资源 id 传入）是一个未被本次修复的隐患写法。

## 复盘与经验
- ** setImageResource 与 setVisibility 必须成对**：多控件互斥切换可见性（1/2 图标二选一）时，任何写入路径都要显式设置全部相关控件的可见性，只改资源不改可见性是常见漏改点。
- **上下电/进程重启是状态残留的放大器**：视图状态跨上下电恢复、回调顺序不定，任何依赖"初始可见性正确"的假设都不可靠，刷新函数应做到幂等、自洽。
- **显示状态机要全覆盖**：蓝牙图标状态=开关×连接×设备类型三个维度，新增分支时容易只顾新路径漏掉旧路径的逆操作。

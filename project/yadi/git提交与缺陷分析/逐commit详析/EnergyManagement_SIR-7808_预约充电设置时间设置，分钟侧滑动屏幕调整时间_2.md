# SIR-7808 · 预约充电分钟滚轮滑动卡顿（Kanzi AAR 二进制更新）

- **提交**：`f2892d3c` | 2026-09-11 | liqingqing | EnergyManagement | bugfix（仅二进制，无源码 diff）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
预约充电设置弹窗中，分钟列滑动调整时间时界面卡顿、出现可见跳变。

## 根因与修复（依据提交信息，无法从 diff 验证）
提交信息完整给出了根因与方案：[why] "松手时直接把偏移量归零，且拖动过程中重复刷新文字状态，造成可见跳变"；[how] "短距离平滑吸附到十分钟档位，并减少无效刷新"。即分钟滚轮（按 `RESERVATION_MINUTE_DISPLAY = {0,10,20,30,40,50}` 十分钟档位吸附）在松手时不是平滑滚动到最近档位，而是把偏移量直接清零；且拖动过程中文字状态被重复刷新，二者叠加造成卡顿与跳变。修复为松手后短距离平滑吸附动画到最近十分钟档位，并裁剪拖动过程中的无效文字刷新。

## 关键代码修改
改动文件：application/Launcher/libs/kanzi-release.aar（二进制更新，481953723 → 481953864 字节）
```diff
diff --git a/application/Launcher/libs/kanzi-release.aar b/application/Launcher/libs/kanzi-release.aar
Binary files a/application/Launcher/libs/kanzi-release.aar and b/application/Launcher/libs/kanzi-release.aar differ
```

## diff 与元数据不符的说明
本提交 diff 仅含 `kanzi-release.aar` 二进制替换，**没有任何源码改动**；修复逻辑封装在预编译的 Kanzi 渲染库内（预约充电时间滚轮由 Kanzi 框架渲染，EnergyManagement 侧 `TimePickerView`/`WheelScrollView` 为另一套备用实现）。以 diff 实际为准：这是一次"aar 二进制更新"型修复，源码级验证只能在 Kanzi 库源仓进行，本仓无法核对吸附动画与刷新裁剪的具体实现，此处如实说明、不做编造。

## 复盘与经验
- 二进制依赖（aar）承载 UI 行为时，缺陷修复表现为"换包"，主仓回溯只能依赖提交信息，务必像本提交一样在 message 里写清 why/how，否则二进制更新完全不可复盘。
- 滚轮/刻度类控件松手行为应使用平滑吸附（fling + snap to nearest tick），"直接归零"是跳变感的最常见来源；拖动过程的状态刷新（文字、颜色）要做脏检查去重。
- Kanzi（游戏引擎级渲染）与原生 View 双轨并存的工程，同一控件可能有两套实现，排障时先确认当前生效的是哪一套（本例卡顿在 Kanzi 侧，而非 e1a8c0ca 里的 WheelScrollView）。

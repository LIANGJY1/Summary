# SIR-6688 · 充电上限进度条紧贴左侧

- **提交**：`a70eebfc` | 2026-08-27 | liqingqing | EnergyManagement | bugfix（UI 微调）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心"充电上限"进度条的滑轨/进度条紧贴控件左边缘，与 UI 设计稿要求的左侧留白不符。

## 根因分析
进度条外观由 layer-list drawable 定义。`seekbar_charge_limit_track.xml`（及 `_disabled` 变体）中，矢量滑轨首段 path 的起点是 `M8,4`，左端圆角直接顶到 drawable 视口左边；`seekbar_slow_charge_track.xml`（及 `_timeout` 变体）的 `progress` item 只设置了 `top/bottom` inset，没有 `left/right`，`<clip>` 进度块从 0 位置开始绘制。两者叠加使视觉上"条体贴左"。修复给矢量首段 path 左移起点让出间隙（8→13，约 5dp），并给 clip 进度层加 `left/right=4dp` inset。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/drawable/seekbar_charge_limit_track.xml、seekbar_charge_limit_track_disabled.xml、seekbar_slow_charge_track.xml、seekbar_slow_charge_track_timeout.xml
```diff
--- application/EnergyManagement/src/main/res/drawable/seekbar_charge_limit_track.xml
-                    android:pathData="M8,4 H315 L319,8 V16 L315,20 H8 A8,8 0,0 1,8,4 Z" />
+                    android:pathData="M13,4 H315 L319,8 V16 L315,20 H13 A8,8 0,0 1,13,4 Z" />
-                    android:pathData="M325,4 H392 A8,8 0,0 1,392,20 H325 L321,16 V8 Z" />
+                    android:pathData="M325,4 H387 A8,8 0,0 1,387,20 H325 L321,16 V8 Z" />

--- application/EnergyManagement/src/main/res/drawable/seekbar_slow_charge_track.xml
     <item
         android:id="@android:id/progress"
-        android:top="10dp"
-        android:bottom="10dp">
+        android:left="4dp"
+        android:right="4dp"
+        android:top="12dp"
+        android:bottom="12dp">
```

## 为什么能修复
矢量轨道首段起点右移 5dp、clip 进度层左右各内缩 4dp 后，滑轨与进度条不再从控件 0 位置起绘，左侧留出设计稿要求的间隙；右段 path 终点 392→387 同步收缩保持总宽一致。四个 drawable（正常/禁用/慢充/超时）成对修改避免状态间错位。纯资源改动，无逻辑风险。

## 复盘与经验
- SeekBar 的视觉边距藏在 layer-list drawable 的 inset 与矢量 path 坐标里，"贴边"类问题优先检查 progress/background 两层 item 的 left/right inset。
- 同一控件的多个状态 drawable（enabled/disabled、正常/超时）属于一个视觉族，改动必须全部同步，否则切状态时跳变。

# SIR-7184 · 能量中心慢充功率文言位置显示错误
- **提交**：`f2467590` | 2026-09-02 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心"慢充功率"行：标签、信息图标与右侧"kw"数值（`tv_slow_charge_thumb_value`）的相对位置错误，与设计稿不符。

## 根因分析
`activity_main.xml` 中"慢充功率"行原本用 `match_parent` + `<Space weight=1>` 弹性占位来分隔左右元素，容器自身靠 `paddingStart/End=16dp`、`paddingTop=18dp` 定位。该结构与设计稿的固定列宽排版不一致：标签 `tv_range_mode` 无固定宽度，图标与数值的间距靠弹性 Space 推挤，在不同文字长度/字体度量下数值无法稳定落在右侧标注位。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/activity_main.xml`
```diff
--- application/EnergyManagement/src/main/res/layout/activity_main.xml
-                    android:paddingStart="16dp"
-                    android:paddingTop="18dp"
-                    android:paddingEnd="16dp"
                     android:paddingBottom="12dp">
                     <LinearLayout
-                        android:layout_width="match_parent"
+                        android:layout_width="276dp"
                         android:layout_height="wrap_content"
+                        android:layout_marginTop="22dp"
+                        android:layout_marginLeft="28dp"
                         android:layout_marginRight="28dp"
                         ...>
                         <TextView android:id="@+id/tv_range_mode"
-                            android:layout_width="wrap_content"
-                            android:layout_height="wrap_content"
+                            android:layout_width="96dp"
+                            android:layout_height="36dp"
                             android:text="慢充功率"
                             android:textSize="24sp" />
                         <ImageView android:id="@+id/iv_slow_charge_info"
-                            android:layout_marginStart="4dp" ...
+                            android:layout_marginStart="6dp" ...
-                        <Space android:layout_width="0dp" android:layout_weight="1" ... />
                         <TextView android:id="@+id/tv_slow_charge_thumb_value"
-                            android:layout_width="wrap_content"
-                            android:gravity="start"
+                            android:layout_width="86dp"
+                            android:layout_height="36dp"
+                            android:layout_marginStart="6dp"
+                            android:gravity="end"
                             android:text="@string/kw" ... />
（下方 seekbar_range_mode 一并从 padding 10dp 改为左右 margin 28dp、宽度固定 220dp 对齐。）
```

## 为什么能修复
把"弹性撑满 + Space 推挤"改为设计稿的固定尺寸排版：行宽 276dp、标签列 96dp、数值列 86dp 右对齐（`gravity="end"`），图标与数值 margin 6dp，"慢充功率"与"kw"数值稳定落在标注位；滑条区域同步改 margin 对齐。风险：固定 dp 在其他语言/字号缩放下可能截断，但该工程 UI 基本为固定中文文案，可接受。

## 复盘与经验
- 左标签右数值的"标注行"用弹性 Space 分隔易受文字宽度影响，设计稿给出固定列宽时直接按固定尺寸实现更稳定。
- 同一卡片内的行、滑条要共享同一套水平 margin（本例统一 28dp），否则视觉上"错位感"明显。
- `textStyle="bold"` 在该工程已被 EnergyTypeface 取代，新布局不应再引入（本 diff 顺带删除了 bold）。

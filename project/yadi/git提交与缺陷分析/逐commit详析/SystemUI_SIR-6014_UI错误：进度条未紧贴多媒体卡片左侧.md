# SIR-6014 · 底部栏媒体进度条未紧贴多媒体卡片左侧

- **提交**：`a896b389` | 2026-08-20 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
底部导航栏的多媒体播放进度条与左侧多媒体卡片之间出现缝隙，未按 UI 设计紧贴卡片左缘。

## 根因分析
缺陷库根因为"ui变更"。`actor_nav_bar.xml` 中 `SeekBar sb_music` 固定宽度 364dp 且右端对齐父容器（`layout_constraintEnd_toEndOf="parent"`），UI 规格调整后卡片与进度条的总宽度关系变化，364dp 的固定值不再与多媒体卡片左缘对齐，留下 2dp 缝隙。修复把 `layout_width` 从 364dp 调整为 366dp，右端不变、左缘外扩 2dp，重新贴齐卡片。一行布局数值修正。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/actor_nav_bar.xml（+1/-1）
```diff
@@ application/SystemUI/src/main/res/layout/actor_nav_bar.xml @@
     <SeekBar
         android:id="@+id/sb_music"
-        android:layout_width="364dp"
+        android:layout_width="366dp"
         android:layout_height="wrap_content"
         app:layout_constraintEnd_toEndOf="parent"
```

## 为什么能修复
进度条宽度与卡片左缘位置重新对齐，缝隙消除。隐患：固定 dp 对齐依赖两侧控件尺寸都不再变化，UI 后续再调卡片宽度时此值需同步维护，更稳的做法是把 SeekBar 左缘直接 constraint 到卡片右缘（`layout_constraintStart_toEndOf`）实现自适应贴合。

## 复盘与经验
- 用固定 dp 宽度 + 单边约束做"贴合"效果，本质是把对齐关系硬编码；控件几何关系应优先用相对约束表达。
- "ui变更"类缺陷的修复往往只是一两个数值，但要在缺陷库留档，否则下次 UI 调整还会踩同一处。

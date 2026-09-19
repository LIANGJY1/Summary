# SIR-6311 · 蓝牙音乐无音源时dock音乐卡片封皮/按键缺省图错误

- **提交**：`1edeea1c` | 2026-08-24 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体（rc/sol 描述偏重"无媒体数据时按钮置灰"，与 diff 实际"缺省封面返回 null"不完全一致，以 diff 为准）

## 问题
蓝牙音乐无音乐源（无媒体数据）时，dock 栏音乐卡片的封皮、控制按键使用了错误的缺省图。

## 根因分析
`MediaForegroundService.getAlbumArtBitmap()` 在专辑封面 URI 为空（`albumArtUri.isNullOrBlank()`）时，用 `BitmapFactory.decodeResource(resources, R.drawable.default_cover)` 兜底，把 BTMusic 应用自带的 `default_cover` 位图塞进 MediaSession 元数据。dock 栏（SystemUI 侧）订阅该元数据：只要拿到了非空 bitmap 就直接显示，不会走自己的缺省图分支——于是蓝牙无音源时，dock 卡片显示的是 BTMusic 的封面兜底图，而非 dock 设计要求的"无音源缺省图"。缺陷库根因概括为"无媒体数据但蓝牙连接且音源为蓝牙时卡片高亮、缺省图加载错误"，代码层面的直接问题是：**数据源不应对"没有数据"伪造一份默认数据**，把"无封面"伪装成"有封面"剥夺了消费端的缺省图决策权。

## 关键代码修改
改动文件：application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt（+1/-1）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/service/MediaForegroundService.kt
@@ getAlbumArtBitmap()
         val albumArtUri = currentAlbumArtUri
         LogUtils.i(tag, "getAlbumArtBitmap IMG uri is $albumArtUri")
         if (albumArtUri.isNullOrBlank()) {
-            return BitmapFactory.decodeResource(resources, R.drawable.default_cover)
+            return null
         }
```

## 为什么能修复
无封面时 `getAlbumArtBitmap()` 返回 null，MediaSession 元数据不再携带伪造的封面位图，dock 栏检测不到 bitmap 便回落到自身正确的缺省图（如 `vector_default_online`），封皮/按键视觉恢复正常。改动一行，语义从"生产者给默认值"变为"生产者如实报告空、消费者给默认值"，职责归位。隐患：`default_cover` 资源若无其他引用会成为死资源；其他直接消费 BTMusic 封面的界面如果依赖这层兜底，需确认它们都有自己的空态处理。

## 复盘与经验
- 数据生产端遇到"无值"就回填本地默认图/默认文案，是最常见的缺省图错位来源：空就是空，让消费端决定如何展示。
- 多级 UI（应用卡片 → dock 栏）共享 MediaSession 数据时，缺省图策略必须约定只在其中一层实现。
- 一行修复也能解决 C 级缺陷——复盘的价值在于识别"伪造数据"这个模式，而不是改动量。

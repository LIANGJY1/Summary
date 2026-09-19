# SIR-1477 · 蓝牙音乐长歌名跑马灯滚动 3 次不停
- **提交**：`e8b26b67` | 2026-06-29 | liujinfeng | Setting(SystemUI 布局) | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
播放长名称蓝牙音乐时，多媒体卡片歌名跑马灯展示一遍后不停止，反复滚动 3 次。

## 根因分析
多媒体卡片歌名 TextView 位于 `application/SystemUI/src/main/res/layout/actor_nav_bar.xml`，启用了 `android:ellipsize="marquee"` 实现跑马灯。Android 的 `TextView` marquee 默认 `marqueeRepeatLimit` 为 3（系统常量 `MARQUEE_REPEAT_LIMIT = 3`），即文本超长时自动滚动 3 遍才停。UI 期望只滚动 1 遍，但布局里从未设置重复次数限制，于是表现为"展示一遍未停下"。缺陷库归因"跑马灯默认执行3次"与代码事实一致。

## 关键代码修改
改动文件：`application/SystemUI/src/main/res/layout/actor_nav_bar.xml`
```diff
--- a/application/SystemUI/src/main/res/layout/actor_nav_bar.xml
@@ -129,6 +129,7 @@
                     android:layout_width="match_parent"
                     android:layout_height="wrap_content"
                     android:ellipsize="marquee"
+                    android:marqueeRepeatLimit="1"
                     android:focusable="true"
                     android:focusableInTouchMode="true"
                     android:maxLines="1"
```

## 为什么能修复
`android:marqueeRepeatLimit="1"` 是系统属性，直接把该 TextView 的滚动次数从默认 3 限制为 1，滚动一遍后停在开头，符合 UI 预期。仅影响这一处布局的跑马灯，无副作用；注意其它用 marquee 的文本如果也需要同样效果要单独设置。

## 复盘与经验
- Android marquee 默认滚 3 次是冷知识，凡是"跑马灯次数不对"优先查 `marqueeRepeatLimit`。
- marquee 生效前提是 `focusable/focusableInTouchMode` + 单行 + 超宽，布局里三者已齐备，说明排查时 UI 效果类问题也要先确认属性组合完整。

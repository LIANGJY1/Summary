# SIR-5975 · 超长歌手名播放一段时间后跑马灯停止滚动

- **提交**：`461ed387` | 2026-08-19 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 本地多媒体

## 问题
播放歌手名称超出 UI 最大宽度的蓝牙歌曲时，跑马灯只滚动一遍就停止，不再循环。

## 根因分析
`activity_main.xml` 中歌手名 TextView 配置了 `android:ellipsize="marquee"` 开启跑马灯，但 `android:marqueeRepeatLimit="1"` 把滚动次数限制为 1 次——首次滚动完成后跑马灯即停止，与"长时间播放期间持续滚动"的预期不符。缺陷库所述"设置模式有误"即指该循环次数属性配置错误。修复将其改为 `-1`（`marquee_repeat_limit` 的"无限循环"取值）。

## 关键代码修改
改动文件：application/BTMusic/src/main/res/layout/activity_main.xml（+1/-1）
```diff
@@ application/BTMusic/src/main/res/layout/activity_main.xml @@
                             android:ellipsize="marquee"
-                            android:marqueeRepeatLimit="1"
+                            android:marqueeRepeatLimit="-1"
                             android:gravity="center"
```

## 为什么能修复
`marqueeRepeatLimit=-1` 是系统约定的无限循环值，TextView 跑马灯在选中态获得焦点后持续滚动，直到文本变化。无副作用；注意跑马灯生效仍依赖 `singleLine=true` 和焦点/选中状态，本布局已有配套属性。

## 复盘与经验
- `marqueeRepeatLimit` 默认为 3，显式写 1 就是"只滚一遍"；做持续滚动效果必须用 -1，这类属性值语义要查文档而不是凭感觉填。
- "一段时间后停止"的 UI 现象优先怀疑动画/滚动次数上限类配置，而不是代码逻辑。
- 一行 XML 改动的 bugfix 也值得入库：布局属性错误与代码逻辑错误同等重要。

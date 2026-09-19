# YD-392989 · 黑夜模式多媒体卡片禁用态按钮亮度与 UI 不一致
- **提交**：`6d285c72` | 2026-07-31 | liqingqing | SystemUI | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
黑夜模式下多媒体卡片状态栏在默认（未连接蓝牙/禁用）状态时，暂停键、下一曲键看起来太亮，与 UI 稿不符。（提交消息 [why] 字段写的是"剩余充电时间单位大小不一致"，与标题/[how] 及 diff 内容不符，以 diff 实际改动为准——本提交只改了按钮禁用态透明度。）

## 根因分析
`NavBarFragment.setViewEnabled(boolean enabled, View view)` 是统一的按钮可用态工具方法：`view.setEnabled(enabled)` 后用 `setAlpha` 表现禁用态，原值为 `0.6f`。UI 设计稿中未连接蓝牙时按钮颜色为 `#4DEEEEEE`，其中 alpha `0x4D = 77/255 ≈ 0.3`，即设计期望的禁用态不透明度是 0.3；代码里的 0.6 相当于 60% 不透明度，黑夜模式深色背景下按钮明显偏亮。这是典型的"设计稿十六进制色值 → 代码 alpha 浮点数"换算错位。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java`（仅 1 行）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
     private void setViewEnabled(boolean enabled, View view) {
         view.setEnabled(enabled);
-        view.setAlpha(enabled ? 1.0f : 0.6f);
+        view.setAlpha(enabled ? 1.0f : 0.3f);
     }
```

## 为什么能修复
禁用态 alpha 从 0.6 调整为设计换算值 0.3（0x4D/0xFF），按钮视觉亮度与 UI 稿 `#4DEEEEEE` 一致。由于 `setViewEnabled` 是所有多媒体控制按钮共用的入口，一处修改即覆盖暂停/下一曲等全部按钮。隐患：该方法若也被白天模式或其他非媒体按钮复用，0.3 的禁用态会全局生效，需确认设计稿在昼间模式的禁用态值是否同为 0.3。

## 复盘经验
- 设计稿 `#AARRGGBB` 转 View alpha 的换算（AA/255）要在设计交付时对齐并写进注释，避免"差不多 0.5、0.6"的目测值；本例 0x4D 精确对应 0.3。
- 提交消息的 [why]/[how] 与实际 diff 不一致（复制粘贴错模板）会给缺陷回溯埋坑，模板字段必须按单填写。
- 通用 UI 工具方法（setViewEnabled）的参数是全局视觉规范载体，改动前要评估所有调用方的主题/模式组合。

# SIR-3401 · 丢失模式页面 UI 更新

- **提交**：`51288c07` | 2026-09-03 | ljl | SystemUI | **feature/UI 更新（非 bugfix，未关联缺陷库）**
- **缺陷库**：未关联单号

## 类型说明
钥匙界面上"丢失模式"页面的 UI 开发（+170/-21 行），非缺陷修复，按特殊情况简述：

- **新增 `LostModePanelView`**（keyguard/widget）：丢失模式红色警示背景面板。注释说明选型原因——XML shape/CardView 只能四角统一圆角，而设计要求"仅顶部左右两角圆角、底部直角贴合导航栏顶边"，故用 `canvas.clipPath` + `addRoundRect` 四角 radii 数组（上两角 `lost_mode_bg_corner_radius`，下两角 0）实现；背景大图 `bg_lost_mode_panel.png`（nodpi，约 2.5MB）按 centerCrop 矩阵缩放绘制。
- **`KeyguardActor.kt`** 扩展约 64 行，把面板接入锁屏 actor 流程；`actor_keyguard.xml` 相应调整。
- 资源配套：新增颜色/尺寸 token（colors.xml、dimens.xml 各 +1/+5），中英文 strings 同步（values-en/strings_en.xml 也更新）。

## 经验提示
- "部分圆角"需求在纯 XML 下无解，自定义 View + `Path.addRoundRect` 的 radii 数组是标准解法（注意 radii 顺序：左上/右上/右下/左下 各 x,y）。
- nodpi 目录放大图（2.5MB）在车机内存敏感场景需关注 Bitmap 尺寸，必要时改用 drawable 分辨率限定或 WebP。

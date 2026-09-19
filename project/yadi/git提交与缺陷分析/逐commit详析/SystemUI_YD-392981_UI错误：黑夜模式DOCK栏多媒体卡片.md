# YD-392981 · 黑夜模式DOCK栏多媒体卡片默认图标颜色与UI不一致

- **提交**：`ddf187bc` | 2026-08-04 | liujinfeng | SystemUI | bugfix
- **缺陷库**：未关联单号（标题含 YD-392981）

## 问题
黑夜模式下 DOCK 栏多媒体卡片无媒体会话时的默认占位图标颜色与 UI 稿不符（旧位图颜色错误）。

## 根因分析
`NavBarFragment.setMediaIcon()/clearMediaInfo()` 中媒体头像占位图使用旧位图资源 `icon_music_bt` / `icon_music_online`，其颜色是按白昼模式绘制的，黑夜模式下没有对应的 night 资源变体，颜色与设计稿不符（颜色值错误类 UI 缺陷）；卡片背景 `vector_music_online` 中还有硬编码色 `#1A1F222A`，同样脱离主题色体系。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java、application/SystemUI/src/main/res/drawable/vector_default_bt.xml、application/SystemUI/src/main/res/drawable/vector_default_online.xml、application/SystemUI/src/main/res/drawable/vector_music_online.xml、application/SystemUI/src/main/res/layout/actor_nav_bar.xml
```diff
// application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java（setMediaIcon/clearMediaInfo 同构）
             musicAvatar.setImageResource(mCurrentMediaEntry.getStatusBarNotification().getPackageName().equals(SysUIConfig.MUSIC_PACKAGE_NAME)?
-                    R.drawable.icon_music_bt:R.drawable.icon_music_online);
+                    R.drawable.vector_default_bt:R.drawable.vector_default_online);
         } else {
-            musicAvatar.setImageResource(R.drawable.icon_music_online);
+            musicAvatar.setImageResource(R.drawable.vector_default_online);
```
```diff
// application/SystemUI/src/main/res/drawable/vector_music_online.xml 背景硬编码色改主题色
-            android:fillColor="#1A1F222A"
+            android:fillColor="@color/divider_default"
```
新增 44dp 矢量 `vector_default_bt.xml`（音符+蓝牙）与 `vector_default_online.xml`（在线音乐），填充色统一为 `@color/icon_default_disabled`（占位/置灰语义色，昼夜模式各自解析）；布局 `actor_nav_bar.xml` 的 `src` 同步改为 `vector_default_online`。

## 为什么能修复
占位图标由"白昼向位图"换成引用主题色的矢量，黑夜模式下 `icon_default_disabled`、`divider_default` 解析为夜间色值，颜色与 UI 稿一致；矢量资源体积小且无昼夜双份位图维护成本。风险：若 UI 稿后续要求占位图比 disabled 更亮，需要另立语义色，不能复用 disabled。

## 复盘与经验
- 位图图标天然不跟随昼夜主题，车机双模式 UI 中占位/默认图标应一律矢量化并引用语义色资源。
- 硬编码 `#AARRGGBB` 混色（如 #1A1F222A 这种带 alpha 的叠加色）一旦散落在 drawable 里就是主题适配的死角，应集中收敛到 colors.xml。
- `setMediaIcon`/`clearMediaInfo` 两个路径设置同一图标，修资源时要用 replace_all 思维全局检索旧资源引用，避免漏改布局里的 `android:src`。

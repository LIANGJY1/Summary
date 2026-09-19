# 无单号 · 蓝牙电话应用增加 10dp 外边距（系统栏处理迁移到主题）

- **提交**：`9b06b94b` | 2026-08-06 | liujinfeng | BTPhone | bugfix（UI 调整 + 沉浸式方案重构）
- **缺陷库**：未关联单号

## 问题
蓝牙电话主界面内容顶到屏幕上边缘，需要按 UI 规范增加 10dp 顶部间距；同时 `MainActivity` 里用代码硬写的沉浸式状态栏/导航栏设置需要收敛。

## 根因分析
布局层面：`activity_main.xml` 根布局没有顶部间距，内容贴顶。工程层面：`MainActivity` 在 `onCreate` 里用约 30 行窗口代码实现沉浸式（`FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS`、`SYSTEM_UI_FLAG_LAYOUT_STABLE | LAYOUT_FULLSCREEN | LAYOUT_HIDE_NAVIGATION`、`setStatusBarColor(TRANSPARENT)`、`setNavigationBarContrastEnforced(false)` 等），这类代码式系统栏处理与主题声明容易冲突且难以复用，本次随边距调整一并迁移到 `themes.xml` 声明式配置。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java`、`application/BTPhone/src/main/res/layout/activity_main.xml`、`application/BTPhone/src/main/res/values-night/themes.xml`、`application/BTPhone/src/main/res/values/themes.xml`
```diff
// application/BTPhone/src/main/res/layout/activity_main.xml
     android:layout_width="match_parent"
     android:layout_height="match_parent"
+    android:paddingTop="@dimen/dp_10"
     android:clickable="true"
     android:focusable="true">
```
```diff
// application/BTPhone/src/main/res/values/themes.xml（values-night 同步）
         <item name="android:windowIsTranslucent">true</item>
+        <item name="android:navigationBarColor">#00000000</item>
+        <item name="android:statusBarColor">#00000000</item>
         <item name="android:fitsSystemWindows">true</item>
```
`MainActivity.java` 删除约 36 行代码式系统栏设置（透明背景、SYSTEM_UI_FLAG 组合、状态栏着色等），效果改由主题属性承载。

## 为什么能修复
根布局 `paddingTop=10dp` 让全部内容下移满足 UI 稿；`navigationBarColor`/`statusBarColor` 透明色写入主题后，原代码式沉浸式设置可整体删除，行为不变而配置点唯一。主题与 `fitsSystemWindows=true` 配合保留原有的避让逻辑。风险：代码删除后依赖主题正确生效，若某 Activity 未使用该主题会丢失透明系统栏；日/夜两份 themes 成对修改，行为一致。

## 复盘与经验
- 沉浸式系统栏优先用主题属性（`statusBarColor`/`navigationBarColor`）声明式实现，仅在需要动态变化时才用代码，可大幅减少 Activity 模板代码。
- UI 边距类提交常捆绑技术债清理，清理时要保证日/夜（values/values-night）等多份资源成对修改。
- `paddingTop` 与 `fitsSystemWindows` 同时存在时要注意避让叠加导致的双重留白，需在真机确认最终间距。

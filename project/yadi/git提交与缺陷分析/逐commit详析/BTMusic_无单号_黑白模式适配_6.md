# 无单号 · BTMusic 黑白模式适配（进度条日夜取色 + 夜间图标资源）

- **提交**：`30b544b4` | 2026-06-30 | daizhecheng | BTMusic | feature
- **关联单**：无

## 需求/目标
蓝牙音乐主界面的黑白模式适配：自绘进度条 `CustomMusicSeekBar` 增加夜间配色分支，播放控制按钮补充 `drawable-night-mdpi` 夜间位图，并删除 BTMusic 私有色板改用语义色。

## 实现结构
- `CustomMusicSeekBar.java`：常量改名 `COLOR_NORMAL_*` → `COLOR_DAY_*`，新增 `COLOR_NIGHT_PLAYED(#EEEEEE)/COLOR_NIGHT_UNPLAYED(#1AEEEEEE)`；`drawProgressBar()` 绘制前按 `isNightMode()` 三分支（disabled/night/day）选色；新增 `isNightMode()` 用 `uiMode & UI_MODE_NIGHT_MASK` 判断。
- 资源：新增 `drawable-night-mdpi/` 下 def_img、play_next/pause/stop/up 5 张夜间位图；`bg_main.xml/bg_tv_connect.xml/bg_view_top_line.xml/activity_main.xml/widget_music_play_control.xml` 引用语义色；删除 `values/colors.xml` 17 行与 `values-night/colors.xml` 5 行旧私有色板。

数据流：uiMode → View 内 `getResources().getConfiguration()` 实时判断 → onDraw 选色（视图重绘时生效）；位图部分由资源限定符 `drawable-night-mdpi` 自动命中。

## 关键代码
```diff
# application/BTMusic/src/main/java/com/yadea/btmusic/view/custom/CustomMusicSeekBar.java
-    private static final int COLOR_NORMAL_PLAYED = Color.parseColor("#1F222A");
-    private static final int COLOR_NORMAL_UNPLAYED = Color.parseColor("#191F222A");
+    private static final int COLOR_DAY_PLAYED = Color.parseColor("#1F222A");
+    private static final int COLOR_DAY_UNPLAYED = Color.parseColor("#191F222A");
+    private static final int COLOR_NIGHT_PLAYED = Color.parseColor("#EEEEEE");
+    private static final int COLOR_NIGHT_UNPLAYED = Color.parseColor("#1AEEEEEE");
...
+        boolean isNightMode = isNightMode();
+        if (isDisabled) { ... } else if (isNightMode) {
+            playedColor = COLOR_NIGHT_PLAYED;
+            unplayedColor = COLOR_NIGHT_UNPLAYED;
+        } else { ... }
+
+    private boolean isNightMode() {
+        int nightModeFlags = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
+        return nightModeFlags == Configuration.UI_MODE_NIGHT_YES;
+    }
```

实现讲解：自定义 View 的颜色不走资源系统，只能运行时判断 uiMode 三分支取色，本提交给出了标准模板（disabled 优先级高于日夜）。与 ab09f686 的 `ResourcesCompat.getColor` 方案相比，这里用 hex 常量硬编码夜间色，代价是色值变更要改代码；好处是不依赖外部资源、进度条重绘即时生效。

## 复盘与要点
- 可复用手法：`onDraw` 内取色必须放在绘制时而非构造期（构造期读 uiMode 后不刷新），本提交的 `isNightMode()` 每帧调用保证了模式切换后下一次重绘即正确。
- 遗留风险：hex 常量与设计稿漂移无编译期约束；更优做法是 init 时缓存 `ResourcesCompat.getColor`，在 `onConfigurationChanged` 里更新缓存字段。
- 删除模块私有色板与 b226c24f（CommonTools）同日呼应，说明色板收敛是分模块推进的统一动作。

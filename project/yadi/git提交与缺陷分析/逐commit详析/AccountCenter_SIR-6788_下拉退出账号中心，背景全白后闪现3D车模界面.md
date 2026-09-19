# SIR-6788 · 下拉退出账号中心背景全白后闪现3D车模
- **提交**：`f9a65d77` | 2026-08-31 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心

## 问题
账号中心界面上拉/下拉触发手势退出（onLapseDownExit）时，屏幕先整体变白，随后才闪现出 3D 驻车车模桌面，过渡生硬且有"闪屏"感。

## 根因分析
两层原因叠加。主题层：`themes.xml` 中账号中心主题配置了 `android:statusBarColor=@color/white`、`android:windowBackground=@android:color/white`，窗口本身是不透明白底——Activity 一旦处于退出/重建瞬间，白色窗口背景与系统栏底色先于内容暴露，形成"全白"一帧。任务栈层：`CenterActivity.onLapseDownExit()` 与 `LoginActivity.onLapseDownExit()` 原实现是 `moveTaskToBack(true)` + `finishAffinity()`，把整个任务栈退到后台再销毁，中间窗口切换经历了"白底窗口 → 桌面"的重绘间隙，才闪现 3D 车模界面。

## 关键代码修改
改动文件：themes.xml、activity_center.xml、activity_login.xml、CenterActivity.kt、LoginActivity.kt
```diff
--- a/application/AccountCenter/src/main/res/values/themes.xml
@@
-        <item name="android:statusBarColor">@color/white</item>
+        <item name="android:windowDrawsSystemBarBackgrounds">true</item>
+        <item name="android:statusBarColor">@android:color/transparent</item>
+        <item name="android:navigationBarColor">@android:color/transparent</item>
         <!-- 防止启动闪屏的关键配置 -->
-        <item name="android:windowBackground">@android:color/white</item>
+        <item name="android:windowIsTranslucent">true</item>
+        <item name="android:background">@android:color/transparent</item>
+        <item name="android:windowBackground">@android:color/transparent</item>
```
```diff
--- a/.../ui/center/CenterActivity.kt（LoginActivity 同）
     override fun onLapseDownExit() {
-        moveTaskToBack(true)
-        finishAffinity()
+        finish()
     }
```
另将 `activity_center.xml`/`activity_login.xml` 的 `android:background="@drawable/bg_main"` 从 `<layout>` 根标签移到内容根布局 `root_layout` 上，避免窗口级背景参与过渡。

## 为什么能修复
窗口改半透明（windowIsTranslucent + 全透明 windowBackground/系统栏）后，账号中心 Activity 之下始终透出底层桌面，finish 时不再有白底帧；退出方式从 finishAffinity 整栈销毁收敛为单 Activity `finish()`，返回时直接露出其下的 3D 驻车桌面，过渡连续。隐患：`windowIsTranslucent` 会禁用部分窗口优化并可能影响动画与焦点，`finish()` 也不再清理任务栈中可能残留的其他 AccountCenter 页面，若存在多级页面需确认各自都会正确结束。

## 复盘与经验
- 车机上"全屏应用浮在 3D 桌面上"的场景，主题必须透明化（windowIsTranslucent + transparent windowBackground），否则任何窗口切换瞬间都会闪底色。
- `finishAffinity()`/`moveTaskToBack()` 是重销毁手段，用于手势返回单页退出往往过重，单纯 finish 更贴合"回到下层界面"的语义。
- 元数据只写了"主题背景色改为透明"，实际 diff 还包含退出逻辑从整栈销毁改为单页 finish——修复是主题与任务栈双管齐下，复盘时应以 diff 为准。

# SIR-8095 · 黑夜模式下控制中心账号默认头像背景色与 UI 不符

- **提交**：`717f4bcb` | 2026-09-10 | caohongliang | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交头标影响等级 D，以缺陷库 C 为准）

## 问题
黑夜模式下，控制中心的账号中心默认头像背景色与 UI 设计稿不一致（夜间背景色错误）。

## 根因分析
默认头像此前是一张位图 `drawable-mdpi/usericon.png`，背景色被"烘焙"在图片里（浅色模式的底色）。位图无法跟随日/夜主题切换，夜间模式下头像底色仍是日间配色，与整体深色控制中心背景不搭（提交 [why]：夜间模式颜色值用错）。另外 mdpi 密度的 PNG 在车机大屏上还会被放大造成发虚。修复方向是删除位图，改用矢量 drawable 并把颜色全部换成主题感知的颜色引用。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/drawable-mdpi/usericon.png（删除，二进制资源）、application/SystemUI/src/main/res/drawable/usericon.xml（新增）（2 文件 +16/-0，Bin 1190→0）
```diff
--- /dev/null
+++ application/SystemUI/src/main/res/drawable/usericon.xml
+<vector xmlns:android="http://schemas.android.com/apk/res/android"
+    android:width="40dp" android:height="40dp"
+    android:viewportWidth="40" android:viewportHeight="40">
+  <path
+      android:pathData="M20,20m-20,0a20,20 0,1 1,40 0a20,20 0,1 1,-40 0"
+      android:fillColor="@color/bg_switch_off"/>
+  <group>
+    <clip-path android:pathData="M20,20m-20,0a20,20 0,1 1,40 0a20,20 0,1 1,-40 0"/>
+    <path
+        android:pathData="M26.921,23.932C31.398,26.408 ...Z"
+        android:fillColor="@color/text_white_default"/>
+  </group>
+</vector>
--- application/SystemUI/src/main/res/drawable-mdpi/usericon.png（删除，二进制更新）
```
（资源同名 `usericon`，位图被矢量替代后所有 `@drawable/usericon` 引用无需改动）

## 为什么能修复
矢量版头像的圆底用 `@color/bg_switch_off`、人形用 `@color/text_white_default`，两个颜色引用均随 values-night 主题切换，夜间模式自动解析为深色底，与控制中心背景一致；日间模式同样回归正确配色。同时矢量化解决了 mdpi 位图在大屏上的缩放模糊。风险极小：需确认夜间资源里 `bg_switch_off` 的取值确为设计稿指定的头像底色，避免同名色在别处语义冲突。

## 复盘与经验
- 位图无法适配日/夜主题，凡是要跟随深色模式换色的图标必须矢量 drawable + 主题色引用（或提供 -night 位图两套）。
- `drawable-mdpi` 这类密度目录适合启动器图标，应用内 UI 图标优先矢量，一次修复同时解决配色与清晰度两个问题。
- 深色模式验收不能只测主界面，控制中心这类聚合入口里的第三方/默认图标（头像、占位图）是漏网高发区。

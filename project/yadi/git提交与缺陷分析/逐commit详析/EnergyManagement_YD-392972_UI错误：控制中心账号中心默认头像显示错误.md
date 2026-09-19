# YD-392972 · 控制中心账号中心默认头像显示错误
- **提交**：`8bbfe3f4` | 2026-07-30 | liqingqing | SystemUI（EnergyManagement 控制中心） | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
控制中心账号区域默认头像与 UI 稿不符（未使用 UI 给定的 icon）。

## 根因分析
控制中心两套布局 `fragment_quick_setting.xml` 与 `fragment_quick_setting_no_hud.xml`（HUD/无 HUD 两个变体）中 `user_icon` 控件的 `android:src` 均引用 `@drawable/vector_user`，该矢量图标与 UI 稿的默认头像不一致。属纯资源引用问题：设计交付了新的头像图 `usericon.png`，但布局没有替换引用。标题写 EnergyManagement，实际 diff 落在 SystemUI 的快速设置布局，以 diff 为准。

## 关键代码修改
改动文件：`application/SystemUI/src/main/res/layout/fragment_quick_setting.xml`、`application/SystemUI/src/main/res/layout/fragment_quick_setting_no_hud.xml`、新增 `application/SystemUI/src/main/res/drawable-mdpi/usericon.png`（二进制图片资源）
```diff
--- application/SystemUI/src/main/res/layout/fragment_quick_setting.xml
                 android:id="@+id/user_icon"
-                android:src="@drawable/vector_user"
+                android:src="@drawable/usericon"
--- application/SystemUI/src/main/res/layout/fragment_quick_setting_no_hud.xml
                 android:id="@+id/user_icon"
-                android:src="@drawable/vector_user"
+                android:src="@drawable/usericon"
```

## 为什么能修复
新增 UI 稿对应的 `usericon.png`，并在两套布局（带 HUD / 不带 HUD）中同步替换 `user_icon` 的 src，所有变体下默认头像一致。改动最小且无副作用；`vector_user` 若无其他引用可择机清理。

## 复盘经验
- 同一界面存在多布局变体（xxx 与 xxx_no_hud）时，换图必须全部同步，否则某变体下复发——本提交两套都改到了。
- 默认头像这类"登录态占位图"建议与 UI 建立资源命名对照（设计稿名 → drawable 名），交付即替换，减少"没使用 UI 上的 icon"这类低级偏差。

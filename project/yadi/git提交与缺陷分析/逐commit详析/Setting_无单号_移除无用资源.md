# 无单号 · 移除无用资源

- **提交**：`9c9f427d` | 2026-07-01 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
清理 Setting 模块被 vector 替代后遗留的 mdpi 位图（11 张），并补齐密码显隐两个还在被引用的 vector（说明它们此前的 mdpi png 被误删过或从未 vector 化）。

## 实现结构
- 删除 `drawable-mdpi/`：`icon_choose(.blue)`、`icon_hi_car(_d)(_night)`、`icon_loading`、`icon_right_d`、`ic_pw_show.png`、`ic_pw_un_show.png` 共 11 张 png。
- 新增 `drawable/ic_pw_show.xml`、`ic_pw_un_show.xml`：眼睛/闭眼 vector，`fillColor` 引用语义色 `text_default_press`。
- 删除 `drawable/loading.xml`（animated-rotate 引用已删的 icon_loading）。

数据流：纯资源层；密码显隐图标由位图改语义色 vector 后，昼夜自动反色，其余为无引用资源清理。

## 关键代码
```diff
# application/Setting/src/main/res/drawable/ic_pw_show.xml（新增）
+<vector xmlns:android="http://schemas.android.com/apk/res/android"
+    android:width="33dp" android:height="25dp"
+    android:viewportWidth="33" android:viewportHeight="25">
+  <path
+      android:pathData="M3.181,12.053C5.257,17.346 ..."
+      android:fillColor="@color/text_default_press"/>
+</vector>
```
```diff
# application/Setting/src/main/res/drawable/loading.xml（删除）
-<animated-rotate ... android:drawable="@drawable/icon_loading"
-    android:duration="1500" ... android:repeatCount="-1" />
```

实现讲解：本提交与主题适配系列的区别在于目的是"瘦身+收尾"——位图全部 vector 化后，mdpi 目录的 png 已无存在必要；`icon_hi_car` 连同 `_d/_night` 四张一起删除，说明 HiCar 入口已不再使用本地位图。删除 `loading.xml` 时连带检查了其唯一引用 icon_loading，避免留下悬空引用。

## 复盘与要点
- 可复用手法：资源清理提交应遵守"删 png → 补同名 vector（若仍被引用）→ 删除引用链上的派生资源（animated-rotate 等）"的顺序，Lint 的 UnusedResources 可作为扫描入口。
- 遗留风险：ic_pw_show/un_show 语义色为 `text_default_press`，若与设计稿的可用色不符需微调；删除的资源若被 `getIdentifier` 动态引用，编译期不会报错，需运行时回归。

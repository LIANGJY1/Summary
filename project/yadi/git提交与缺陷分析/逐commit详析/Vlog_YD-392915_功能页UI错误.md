# YD-392915 · Vlog功能页UI错误（背景色/卡片底色/按钮尺寸与设计稿不符）

- **提交**：`adbb3edf` | 2026-07-21 | daizhecheng | Vlog | bugfix（纯 UI 资源调整）
- **缺陷库**：未关联单号（缺陷库无记录，单号为 YD 平台单）

## 问题
Vlog 功能页（`CameraPairedActivity`）视觉与设计稿不符：页面主背景色错误、左右两个容器卡片底色不对、连接按钮与"更多"按钮的尺寸/间距错位。

## 根因分析
纯 UI 资源问题：`bg_main.xml` 引用的 `@color/bg_application` 颜色值与设计稿不一致（该全局色被其他页面共用，单改全局色有连带风险）；`camera_pair_activity.xml` 中 `left_container`/`right_container` 复用了白色圆角底 `btn_bg_radio16_white`，而设计稿要求"另一种灰色"；`rl_connect` 宽 244dp 放不下文案且误设了 `orientation`（对 RelativeLayout 无意义），`btn_connect_more` 用 `src` 布置图标导致缩放/层叠与设计不符。

## 关键代码修改
改动文件：application/Vlog/src/main/res/drawable/bg_main.xml；application/Vlog/src/main/res/drawable/btn_bg_radio16_othergrey.xml（新增）；application/Vlog/src/main/res/layout/camera_pair_activity.xml
```diff
--- a/application/Vlog/src/main/res/drawable/bg_main.xml
-            <solid android:color="@color/bg_application" />
+            <solid android:color="#f0f3fa" />
--- a/application/Vlog/src/main/res/layout/camera_pair_activity.xml
-                android:background="@drawable/btn_bg_radio16_white"
+                android:background="@drawable/btn_bg_radio16_othergrey"
                 <!-- left_container：marginStart dp46→dp40；right_container：补 marginEnd dp40 -->
-                    android:layout_width="244dp"
+                    android:layout_width="300dp"
                     android:layout_height="60dp"
                     ...
-                    android:orientation="horizontal"
-                    android:src="@drawable/icon_more"
+                    android:background="@drawable/icon_more"
+                    android:layout_marginStart="@dimen/dp20"
```
新增 `btn_bg_radio16_othergrey.xml`：pressed/常态均为 `@color/othergray_white`、圆角 dp16 的 selector。

## 为什么能修复
按设计稿逐项对齐：背景色写死 `#f0f3fa`（避免动全局色波及他页）、卡片改用新增的专用灰色底、按钮加宽到 300dp 并修正图标布置方式与间距。属于低风险视觉修正；隐患仅在于 `#f0f3fa` 绕过了颜色集中管理，后续换主题需记得回改此文件。

## 复盘与经验
- 共用色被单一页面"占用"时，优先新建专用 drawable/color，而不是改全局值——本提交正是用新文件规避连带影响。
- RelativeLayout 上写 `orientation` 是从 LinearLayout 复制属性遗留的无效代码，review 时应顺手清理。
- UI 类 bugfix 提交也应写清 what/why/how（本单的 what/why/how 全是"UI"，事后无法追溯），至少注明"与设计稿哪一项不符"。

# YD-392741 · "进行控制"字体被挡住与UI不符

- **提交**：`98763f37` | 2026-06-27 | daizhecheng | Vlog（批量数据标注为 Setting，diff 实际全在 application/Vlog，以 diff 为准）| bugfix（UI 重排）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
Vlog 首页（`activity_home.xml`）的提示文案"支持连接 Insta 360 运动相机，可进行控制、回放等功能"中"进行控制"等字被遮挡/截断，首页视觉与新版 UI 设计稿不符。

## 根因分析
旧首页布局只有一个全屏 `iv_touch_head`（背景 `main_bg`）加一个"相机配对"按钮，引导文案塞在按钮周边的小控件里，没有为设计稿的三段文案（主标题/副标题/底部提示）预留定宽定高的 TextView，文字随容器宽度换行溢出后被相邻元素压住。修复方式不是微调 margin，而是按 UI 稿整体重排：引入 `main_start_vlog`/`main_start_vlog2`/`main_start_vlog3` 三条字符串资源，配 631dp 定宽、120dp 定高、`lineSpacingExtra`、`translationY` 精确复刻设计稿排版，并更换底图（新增 `main_bg2.png`、`main_group.png`、`start_main.png`）。

## 关键代码修改
改动文件：application/Vlog/build.gradle、res/drawable-mdpi/main_bg2.png 与 main_group.png 与 start_main.png（二进制图片新增）、res/drawable/btn_up_p.xml、res/layout/activity_home.xml、res/values/strings.xml、res/values-en/strings.xml

```diff
--- application/Vlog/src/main/res/values/strings.xml
@@ 新增三段文案资源
+    <string name="main_start_vlog">开始旅拍生活</string>
+    <string name="main_start_vlog2">支持连接 Insta 360  运动相机，可进行控制、回放等功能</string>
+    <string name="main_start_vlog3">相机需先开机，并在车辆附近。</string>
```

```diff
--- application/Vlog/src/main/res/layout/activity_home.xml
@@ 按设计稿定宽定高排布，杜绝文字被压
+            <TextView
+                android:id="@+id/main_txt1"
+                android:layout_width="631dp"
+                android:layout_height="120dp"
+                android:layout_marginLeft="65dp"
+                android:layout_marginTop="112dp"
+                android:gravity="center_horizontal|start"
+                android:lineSpacingExtra="14sp"
+                android:text="@string/main_start_vlog"
+                android:textColor="@color/text_default_default"
+                android:textSize="80sp"
+                android:translationY="-6.96sp"
+                app:layout_constraintLeft_toLeftOf="parent"
+                app:layout_constraintTop_toTopOf="parent" />
```

```diff
--- application/Vlog/src/main/res/drawable/btn_up_p.xml
@@ 硬编码色 → 颜色令牌
-            <solid android:color="#404C60" />
+            <solid android:color="@color/bg_button_suggest" />
```
另：`build.gradle` 新增 `implementation files('../../component/commonlibs/NsrCommonUI.aar')`（二进制 aar 依赖引入，为新 UI 组件/资源提供支持）。

## 为什么能修复
把易被遮挡的行内文字改为独立、定宽（631dp）、定高（120dp/36dp/30dp）并约束到父布局的 TextView，文字有了专属绘制区，不再被按钮/图片压住；底图与颜色令牌同步替换保证整体视觉一致。隐患：布局大量使用绝对 dp 定位（marginLeft=65dp、marginTop=112dp、translationY 负值微调），是"按设计稿像素还原"的写法，换分辨率或字体缩放后仍可能复现遮挡；`translationY="-6.96sp"` 用 sp 做位移单位也不规范。

## 复盘与经验
- **"文字被挡"优先检查布局结构**：行内文字与图片/按钮重叠时，给文字独立定宽定高 + 约束，比调 margin 更根本。
- **设计稿还原要用尺寸令牌而非拍脑袋**：lineSpacingExtra、translationY 等参数应从设计稿标注导出并注释来源，便于后续校对。
- **颜色硬编码换 `@color` 令牌**（#404C60 → bg_button_suggest）是夜间模式/主题化的前置条件，UI 修复时应顺手完成。

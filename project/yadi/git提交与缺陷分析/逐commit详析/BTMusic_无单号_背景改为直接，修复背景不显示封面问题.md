# 无单号 · [SIR-XXX] 背景改为直角，修复背景不显示封面问题
- **提交**：`c2f5581a` | 2026-09-11 | dufan | BTMusic | feature（实为缺陷修复 + UI 调整混合，按 bugfix 模板分析）
- **关联单**：无（SIR-XXX 占位单号）

> 注：标题"背景改为直接"应为"直角"的笔误。本提交一半是 UI 直角化调整，一半是"封面模糊背景不显示"的缺陷修复，后者按 bugfix 模板展开。

## 问题
蓝牙音乐主界面：专辑封面的模糊放大背景没有显示出来；同时按新 UI 规范需把背景从顶部圆角改为直角。

## 根因分析
旧代码把 Glide 加载出的封面 Bitmap 转成 Drawable 后，设置到了 `clContent`（最外层根容器）的 background 上：
- 外层 `cl_content` 在布局里另配了 `android:background="@drawable/bg_main"`，且承载整页内容，模糊封面被压在底层/被覆盖，视觉上"不显示"；
- 真正应当作为"封面氛围背景"的容器是内容卡片 `clForgetContent`，连接状态切换逻辑（`mIsConnected` 分支）也正是给 `clForgetContent` 换背景——两条路径的目标容器不一致，属于开发时的笔误级错位。

另外 `resources.getDrawable(id, null)` 为废弃 API，且未随日夜模式正确解析。

## 关键代码修改
```kotlin
// application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（Glide onResourceReady）
-   setupTopRoundedClip()
    val drawable = bitmap.toDrawable(resources)
-   mBinding.clContent.background = drawable
+   mBinding.clForgetContent.background = drawable
```

```kotlin
// application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt（断连分支）
-   mBinding.clForgetContent.background = resources.getDrawable(R.drawable.bg_main, null)
+   mBinding.clForgetContent.setBackgroundResource(R.color.bg_application)
```

```xml
<!-- application/BTMusic/src/main/res/layout/activity_main.xml：外层不再配背景 -->
 <androidx.constraintlayout.widget.ConstraintLayout
     android:id="@+id/cl_content"
     android:layout_width="match_parent"
     android:layout_height="match_parent"
-    android:background="@drawable/bg_main"
     android:orientation="vertical">
```
配套改动：`bg_main.xml` 删除 36dp 顶部圆角；`background_nomal.png`（含 night 版）二进制更新为直角切图。

## 为什么能修复
把模糊封面 Drawable 的落点从被覆盖的根容器 `clContent` 改到与连接状态分支一致的 `clForgetContent`，背景层与内容层关系理顺，封面背景得以露出；外层布局去掉重复背景、`bg_main` 去掉圆角后，视觉上统一为直角。`setBackgroundResource` 走资源编译期解析，同时消除了废弃 API 调用。

## 复盘与经验
- **同一视觉槽位的多处赋值必须收敛到同一容器**：本 bug 的本质是"背景该给谁"在两条代码路径里写成了两个 View。类似"动态背景/封面"需求，建议在布局层预留专门的背景 View，所有路径只改它一个。
- **UI 改版与 bugfix 混在一个提交**：直角化（资源/布局）与封面修复（逻辑）耦合提交，回滚与归因都不方便；标题还带错别字（"直接"），检索成本高。拆成两个提交更利于缺陷库关联。
- **日夜模式资源**：背景图更新时 `drawable-mdpi` 与 `drawable-night-mdpi` 成对替换，是主题适配的正确姿势，值得沿用。

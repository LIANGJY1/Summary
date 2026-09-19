# YD-392968 · BTMusic 默认封面与 UI 不一致
- **提交**：`5e59819a` | 2026-07-30 | hedeyuan | BTMusic | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
蓝牙音乐无媒体信息时显示的默认封面图与 UI 设计稿不一致（默认封面图不对）。

## 根因分析
代码中所有默认封面占位均引用旧图 `R.drawable.def_img`：`MainActivity.kt` 中初始化时 `mBinding!!.ivPic.setImageResource(R.drawable.def_img)`，以及 Glide 加载专辑封面的 `RequestOptions().placeholder(R.drawable.def_img).error(R.drawable.def_img)`，断连/清空分支、`activity_main.xml` 中 `iv_pic` 的 `android:src` 也同样引用。旧资源图与设计稿不符，属于典型的"占位图资源未随 UI 稿更新"问题，无逻辑错误。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`（5 处引用替换）、`application/BTMusic/src/main/res/layout/activity_main.xml`、新增 `application/BTMusic/src/main/res/drawable-mdpi/default_cover.png`（二进制图片资源）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt
-                            .placeholder(R.drawable.def_img).error(R.drawable.def_img)
+                            .placeholder(R.drawable.default_cover).error(R.drawable.default_cover)
--- application/BTMusic/src/main/res/layout/activity_main.xml
-                        android:src="@drawable/def_img"
+                        android:src="@drawable/default_cover"
```

## 为什么能修复
新增符合 UI 稿的 `default_cover.png`，并把 Kotlin 代码（初始化、Glide placeholder/error、断连分支、恢复默认分支）和布局 XML 中的全部 5 处 `def_img` 引用一次性替换，封面显示与设计稿一致。隐患：`def_img` 未删除，若其他模块仍引用需确认（本提交范围内 BTMusic 已无残留引用）。

## 复盘与经验
- 占位图/default 图是 UI 走查高频失分点：一个图往往散落在"代码 setImageResource + Glide placeholder/error + 布局 src"三处，必须全量搜索替换，漏一处就会出现"有时对有时错"的偶发 UI 差异。
- drawable 命名要有语义（def_img → default_cover），换图时全局搜索旧名可防遗漏。

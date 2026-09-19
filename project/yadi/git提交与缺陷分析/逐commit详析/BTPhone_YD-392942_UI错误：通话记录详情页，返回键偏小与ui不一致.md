# YD-392942 · 通话记录详情页返回键偏小与 UI 不一致

- **提交**：`599a78f6` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392942，defs 无条目）

## 问题
通话记录详情页（`fragment_call_log_desc.xml`）左上角返回键显示偏小，线稿粗细、颜色与设计稿不符。

## 根因分析
两个因素叠加导致返回键视觉偏小：
1. `ImageView(ivBack)` 设置了 `android:padding="@dimen/dp_10"` 并带 `more_bg_selector` 背景，padding 把矢量图标在固定视图内压缩绘制，实际图形比设计稿小一圈，且多余的按压背景框不属于该页设计。
2. 矢量资源 `icon_back.xml` 本身 `strokeWidth="3"`，比设计稿线宽粗；颜色用 `text_default_default`（文字色）而非图标语义色。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/drawable/icon_back.xml；application/BTPhone/src/main/res/layout/fragment_call_log_desc.xml

```diff
--- application/BTPhone/src/main/res/drawable/icon_back.xml
       android:pathData="M20,25.333L12.669,16.781C12.284,16.331 12.284,15.668 12.669,15.219L20,6.667"
-      android:strokeWidth="3"
+      android:strokeWidth="2"
       android:fillColor="#00000000"
-      android:strokeColor="@color/text_default_default"
+      android:strokeColor="@color/icon_default_default"
       android:strokeLineCap="round"/>
--- application/BTPhone/src/main/res/layout/fragment_call_log_desc.xml
         android:src="@drawable/icon_back"
         app:layout_constraintTop_toTopOf="parent"
         app:layout_constraintStart_toStartOf="parent"
-        android:background="@drawable/more_bg_selector"
-        android:padding="@dimen/dp_10"/>
+        android:layout_marginStart="@dimen/dp_20"
+        />
@@
         android:textSize="26sp"
         app:layout_constraintTop_toTopOf="parent"
         app:layout_constraintStart_toEndOf="@+id/ivBack"
-        android:layout_marginStart="@dimen/dp_10"
         android:gravity="center_vertical"/>
```

## 为什么能修复
去掉 `padding=10dp` 后图标按 ImageView 全尺寸绘制，视觉变大；去掉背景选择器还原干净样式；矢量线宽 3→2、颜色改图标语义色 `icon_default_default` 后与设计稿一致。标题的 `marginStart=10dp` 一并移除、返回键加 `marginStart=20dp`，微调与设计稿的间距对齐。风险极小，需注意返回键可点击热区是否因去 padding 而缩小（触控体验层面）。

## 复盘与经验
- **"图标偏小"先查 padding 再查资源本身**：ImageView 的 padding 直接压缩 src 绘制区，比改图标尺寸更隐蔽；带背景 + padding 的组合常是复用他人布局时遗留的。
- **矢量图标线宽/颜色应由设计切图决定**：`strokeWidth`、语义色（`icon_default_default` vs 文字色）是设计规范的载体，随手改线宽会造成全局不一致（`icon_back.xml` 是共享资源，本改动会影响所有引用处，需全局检查）。
- **共享 drawable 的修改要评估影响面**：icon_back 类公共图标调整线宽等于全局换图标，正确做法是确认所有引用页都接受新样式，或为新样式另建资源。

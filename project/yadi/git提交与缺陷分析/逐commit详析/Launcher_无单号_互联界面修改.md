# 无单号 · 互联界面修改（车联图标换用 SquircleImageView）

- **提交**：`2d471fa0` | 2026-07-01 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
互联（CarConnect）列表界面的视觉调整：把 CarPlay / HiCar / CarLink 三个入口图标从普通 `ImageView` 换成自定义的圆角方形（squircle）控件，并清理桌面应用 item 布局中的注释废代码。

## 实现结构
- 修改 `application/Launcher/src/main/res/layout/fragment_carconnect_list.xml`：3 处图标控件替换。
- 修改 `application/Launcher/src/main/res/layout/item_app.xml`：删除 2 段被注释掉的 `iv_tip` / `iv_delete_icon` 布局代码（约 20 行）。

数据流不变，纯 XML 视图层改动：`SquircleImageView` 来自公共控件库 `com.yadea.common.widgets`，在 XML 中直接替换节点类型即可生效，无需改 Java/Kotlin 代码。

## 关键代码
```diff
--- a/application/Launcher/src/main/res/layout/fragment_carconnect_list.xml
@@ -38,7 +38,7 @@
-                <ImageView
+                <com.yadea.common.widgets.SquircleImageView
                     android:id="@+id/iv_carplay_icon"
                     android:layout_width="120dp"
                     android:layout_height="120dp"
```

```diff
--- a/application/Launcher/src/main/res/layout/item_app.xml
@@ -15,26 +15,6 @@
-    <!--    <ImageView-->
-    <!--        android:id="@+id/iv_tip"-->
-    ...（iv_tip / iv_delete_icon 两段注释代码整体删除）
```

实现讲解：控件替换只改了节点标签，id、尺寸、约束全部保留，说明 `SquircleImageView` 是 `ImageView` 的直接子类（或兼容其属性集），这是自定义形状控件最常见的做法——继承后重写 `onDraw`/`setOutlineProvider` 实现连续圆角，业务侧零成本接入。顺带删掉的注释代码属于长期遗留的"编辑器 graveyard"，清掉可以降低误启用风险。

## 复盘与要点
- 公共控件（`com.yadea.common.widgets.SquircleImageView`）沉淀在 Common 层供各模块复用，避免每个模块各写一套圆角实现——可复用的跨模块 UI 规范落地方式。
- 纯布局替换提交影响等级标 D（极低），测试范围"无"，符合视觉微调的风险定位。
- 遗留风险：若其他页面仍用 `ImageView` 加载同样的互联图标，会出现圆角风格不一致，需要视觉走查覆盖全部入口页。

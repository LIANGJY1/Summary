# 无单号 · 新 UI 修改圆角样式（Setting/CommonTools 圆角规格统一）

- **提交**：`f2da7a47` | 2026-07-03 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
按新版 UI 设计规范统一 Setting 及公共库的圆角半径：大圆角容器从 36dp 收敛到 30dp，按钮/分段选择/卡片类从 16~24dp 统一收敛到 12dp。

## 实现结构
修改 13 个 shape drawable（Setting 2 个 + CommonTools 11 个），全部是 `<corners>` 数值调整，不涉及代码逻辑：
- `shape_bg_application.xml` / `shape_round_top_left.xml`：顶部圆角 36dp → 30dp；
- `shape_btn_white_selected.xml`、`bg_text_rg.xml`、`bg_text_rg_item.xml`、`shape_setting_rg_bg.xml` 等：radius 16/18/24dp → 12dp；
- `shape_setting_gray_round_bg.xml`、`shape_setting_seat_heat_bg.xml`：四角重复写法简化为 `android:radius="12dp"`。

这些 drawable 分布在 `application/Setting/src/main/res/drawable/` 与 `component/CommonTools/src/main/res/drawable/`，Common 侧的（分段按钮、坐垫加热卡片等）被多个模块共享，改一处全局生效。

## 关键代码
```diff
--- a/component/CommonTools/src/main/res/drawable/shape_setting_gray_round_bg.xml
@@ -3,8 +3,5 @@
     <solid android:color="@color/bg_segmentbutton_default" />
     <corners
-        android:topLeftRadius="24dp"
-        android:topRightRadius="24dp"
-        android:bottomLeftRadius="24dp"
-        android:bottomRightRadius="24dp" />
+        android:radius="12dp" />
 </shape>
```

```diff
--- a/application/Setting/src/main/res/drawable/shape_bg_application.xml
@@ -2,8 +2,8 @@
     <solid android:color="@color/bg_application" />
     <corners
-        android:topLeftRadius="36dp"
-        android:topRightRadius="36dp"
+        android:topLeftRadius="30dp"
+        android:topRightRadius="30dp"
```

实现讲解：新 UI 走的是"少档位圆角体系"（30dp 容器 / 12dp 控件），这次把历史遗留的 16/18/24dp 杂档全部归拢。顺带把"四角分别写 24dp"的冗余简化成单个 `android:radius`，语义等价、可读性更好。

## 复盘与要点
- 圆角档位应沉淀为 dimen 资源（如 `radius_container`/`radius_control`）而非散落字面量，否则每次 UI 改版都要像本次一样 13 个文件逐个扫——本批次的 `2d471fa0` 用 `SquircleImageView` 是另一条"控件级统一"路线，两条路线可互补。
- 改 CommonTools 的公共 drawable 影响所有引用模块，影响等级标 C 合理，测试范围应覆盖所有复用该背景的页面而非仅 Setting。
- 大圆角改小通常伴随深浅两套主题的 shape 同步（本批次另有黑白模式适配提交），圆角与配色规格要成对维护。

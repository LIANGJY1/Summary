# 无单号 · UI优化

- **提交**：`e55e9aef` | 2026-08-28 | sgh | Setting | feature（纯 UI/资源调整）
- **关联单**：无

## 需求/目标
驾驶页布局与视觉微调：能量回收档位控件从页面底部上移到驾驶模式之后、湿滑模式之前；两个 vector 图标从硬编码色值改为主题色引用；删除驾驶页残留的 TCS 模式映射死代码。

## 实现结构
- `fragment_driving.xml`：`rg_energy_recovery_mode`（ImageTextRadioGroup）整块迁移位置并调整间距（dp_40→dp_36、湿滑模式 dp_36→dp_32）。
- `DrivingFragment.kt`：删除 30 行 `getTcsModeValue/getTcsModeUiIndex` 私有映射函数（UI 已无 TCS 档位控件，属清理死代码）。
- `light_loom_position.xml`、`tab_indicator_img.xml`：`fillColor` 由 `#3C4558`、`#1F222A` 硬编码改为 `@color/icon_default_default`、`@color/text_default_default`，适配换肤/日夜间主题。

## 关键代码
```diff
--- a/application/Setting/src/main/res/drawable/tab_indicator_img.xml
@@ -5,5 +5,5 @@
     android:viewportHeight="3">
   <path
       android:pathData="M1.5,0L58.5,0A1.5,1.5 0,0 1,60 1.5L60,1.5A1.5,1.5 0,0 1,58.5 3L1.5,3A1.5,1.5 0,0 1,0 1.5L0,1.5A1.5,1.5 0,0 1,1.5 0z"
-      android:fillColor="#1F222A"/>
+      android:fillColor="@color/text_default_default"/>
 </vector>
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DrivingFragment.kt
@@ -220,36 +220,6 @@
-    private fun getTcsModeValue(position: Int): Int {
-        return when (position) {
-            0 -> 3  // 关闭
-            1 -> 0  // 标准
-            2 -> 1  // 运动
-            3 -> 2  // 雨天
-            else -> 0  // 默认标准
-        }
-    }
-
-    private fun getTcsModeUiIndex(modeValue: Int): Int {
-        ...
-    }
```

实现讲解：本提交为低风险 UI 项，一句话概括即可。值得注意的手法是把 vector 着色收敛到颜色资源，为日夜间/主题切换铺路；删除的 TCS 映射函数与 `e0903b2f` 驾驶模式映射同构，说明该功能档位已在前期提交中被移除、代码滞后清理。

## 复盘与要点
- drawable 内硬编码色值是换肤改造的最大阻力，向 `@color/*` 收敛应作为日常提交顺手做的事。
- 删除 UI 控件时同步清理其专属映射/监听代码，避免"孤儿代码"长期滞留（本提交清的就是前序功能裁剪的尾巴）。

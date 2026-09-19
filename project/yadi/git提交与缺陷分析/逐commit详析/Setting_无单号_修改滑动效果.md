# 无单号 · Setting 滑动视觉效果调整（资源类）

- **提交**：`e15e7e0c` | 2026-07-28 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
调整设置页弹窗/驾驶页的滑动渐变遮罩视觉：把写死的十六进制渐变色替换为主题色资源，并同步微调相关布局。

## 实现结构
类型：**纯资源提交**（无代码逻辑改动）。
改动 7 个文件，365 增 / 327 删，全部为 res：
- `drawable/bg_bottom_linear_gradient.xml`、`vir_bottom_bg.xml`、`vir_top_bg.xml`：渐变色由 `#00EAEDF2`/`#EAEDF2`、`#00EBF2FA`/`#EBF2FA` 等硬编码改为 `@color/bg_dialog` / `@color/bg_dialog_maskhidden` 引用
- `layout/dialog_bluetooth_child.xml`、`dialog_connect_child.xml`、`dialog_global_wake_up.xml`、`fragment_driving.xml`：配合滑动效果的布局属性调整

## 关键代码
```diff
--- a/application/Setting/src/main/res/drawable/bg_bottom_linear_gradient.xml
@@ -6,8 +6,8 @@
     <gradient
-        android:endColor="#00EAEDF2"
+        android:endColor="@color/bg_dialog_maskhidden"
         android:angle="90"
-        android:startColor="#EAEDF2" />
+        android:startColor="@color/bg_dialog" />
```
实现讲解：滑动时顶部/底部渐隐遮罩原先是写死色值，与弹窗背景色 `#EBF2FA` 强耦合；改为颜色资源引用后，主题/深浅色切换时可跟随变化，同时 vector 渐变 `vir_top_bg.xml` 用 `aapt:attr` 内联 gradient 同步替换。

## 复盘与要点
- 渐变遮罩类 drawable 应统一走颜色资源而非硬编码 hex，避免多处色值漂移。
- 大量"布局重排 + 属性微调"的 diff（如 dialog_global_wake_up.xml 297 行变化）多为格式化噪声，评审时应重点看色值与尺寸属性行。
- 遗留风险：`vir_top_bg.xml` 注释仍写死 `#EBF2FA`，文档与实现已不一致。

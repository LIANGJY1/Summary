# 无单号 · 黑夜模式适配（AccountCenter 颜色令牌化）

- **提交**：`346f3de1` | 2026-07-15 | hedeyuan | AccountCenter | feature
- **关联单**：无

## 需求/目标
账号中心全局接入昼夜主题：把布局/drawable 中写死的颜色字面量替换为主题感知的颜色令牌（`text_default_default`、`bg_dialog` 等），并让 Activity 在 uiMode 变化时走系统重建换肤。

## 实现结构
改动 10 个文件（+44/-34）：
- `AndroidManifest.xml`：多个 Activity 的 `configChanges` 移除 `uiMode`——即昼夜切换时不再拦截，交给系统按 `-night` 资源自动重建。
- 布局（`activity_center/login`、`dialog_exit_login`、`dialog_qr_code_login`）：`#20232B/#666666/#007AFF/#FFFFFF` 等硬编码色全部替换为 `text_default_default/text_default_press/text_blue_default/text_white_default` 令牌。
- drawable：`#F0F2F5→@color/bg_dialog`、`#404C60→@color/bg_button_suggest`、`#E5E5E5/#FFFFFF→@color/bg_button_default`、`#4DBAC3D8→@color/bg_application`；新增返回箭头矢量图 `more.xml`（fillColor 用 `icon_default_press`，主题感知的矢量图标）。

数据流：系统切深色 → Activity 重建 → LayoutInflater 以新 uiMode 解析 → 令牌色自动命中 `values-night` 定义，无需业务代码参与。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/AndroidManifest.xml
-            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|uiMode|locale|layoutDirection|touchscreen"
+            android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|locale|layoutDirection|touchscreen"
```
```diff
--- a/application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml（节选）
-                    android:textColor="#20232B"
+                    android:textColor="@color/text_default_default"
```
实现讲解：这是与 Vlog `79635079` 相反的换肤路线——不写 `switchTheme()`，而是"去 configChanges + 资源令牌化"，把换肤交还系统重建机制。改动全部落在资源层，代码零侵入；代价是切换瞬间 Activity 重建（状态需 onSaveInstanceState 兜底）。

## 复盘与要点
- 两条换肤路线的选择依据：页面轻、状态易存（表单少）→ 走系统重建 + 资源令牌（本提交，维护成本最低）；页面重、状态复杂（媒体播放）→ 走 `configChanges + 手动 switchTheme`（Vlog）。团队内两种并存时应按页面类型显式约定。
- 令牌命名（`text_default_default` = 语义_状态）可直接复用为车机多主题设计规范，硬编码色值在扫描规则中可设为禁用项防回潮。
- 遗留风险：`LoginDialogActivity` 等弹窗型 Activity 若仍保留 `uiMode`（本提交只摘了部分），切换深色时行为会不一致；重建路径需回归登录态保持逻辑。

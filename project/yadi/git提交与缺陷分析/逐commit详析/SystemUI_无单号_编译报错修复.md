# 无单号 · 编译报错修复（布局约束引用回退）

- **提交**：`9ff2da17` | 2026-08-04 | liujinfeng | SystemUI | bugfix（编译修复）
- **缺陷库**：未关联单号

## 问题
前一笔提交 5d4e888b 把 `fragment_quick_setting.xml` 中 HUD 亮度条的约束从 `@id/hud_switch` 改为 `@id/hud_auto_switch`，导致该布局编译失败（`hud_auto_switch` 这个 id 在本布局中并不存在，只有 `@+id/hud_switch`，AAPT 解析 `@id/` 引用时找不到资源即报错）。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/layout/fragment_quick_setting.xml
```diff
// application/SystemUI/src/main/res/layout/fragment_quick_setting.xml
-                app:layout_constraintStart_toEndOf="@id/hud_auto_switch" />
+                app:layout_constraintStart_toEndOf="@id/hud_switch" />
```

## 为什么能修复
恢复引用本布局中真实存在的 `@+id/hud_switch`，`@id/` 前向引用恢复可解析，编译通过。注意：5d4e888b 提交信息里称这是"约束错链修正"，实际却引入了编译错误——该 id 应该在 `fragment_quick_setting_no_hud.xml`（无 HUD 变体布局）中才存在，两个布局的控件清单并不相同，修改时没有区分。

## 复盘与经验
- `@id/xxx` 引用的是"已定义过的 id"，跨布局复制控件时 id 集合不同，`@id/` 引用必须随布局逐一核对，改完要本地编译验证。
- 布局 XML 不是编译安全的：引用错 id 在运行时才崩（InflaterException）或编译期才报，纯资源类提交更需快速回归编译。
- 连续两个提交一改一回，说明前一笔提交未经过编译自检就入库；"fix build"提交应注明是被哪笔提交破坏的，便于追溯。

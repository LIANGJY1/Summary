# 无单号 · 优化项目依赖（Vlog 摘除 LinkApi + 返回箭头颜色修正）

- **提交**：`073bec55` | 2026-07-03 | dufan | Vlog | feature
- **关联单**：无

## 需求/目标
Vlog 模块依赖治理的收尾小提交：`api project(':component:LinkApi')` 降级删除，并把返回箭头图标描边色从误用的确认按钮色换成文字默认白。

## 实现结构
- 修改 `application/Vlog/build.gradle`：删除 1 行 `api project(':component:LinkApi')`。
- 修改 `application/Vlog/src/main/res/drawable/icon_back.xml`：vector 描边色 `@color/bt_confirm` → `@color/text_white_default`。

无代码逻辑改动，构建层面 LinkApi 不再参与 Vlog 的依赖图，运行时视觉上返回箭头恢复正常白色。

## 关键代码
```diff
--- a/application/Vlog/build.gradle
@@ -85,7 +85,6 @@ dependencies {
     implementation libs.cymchad.baserecyclerviewadapterhelper
     implementation(libs.lottie)
     implementation(libs.flowlayout)
-    api project(':component:LinkApi')
     implementation project(':component:Carlib')
```

```diff
--- a/application/Vlog/src/main/res/drawable/icon_back.xml
@@ -7,6 +7,6 @@
         android:strokeWidth="3"
-        android:strokeColor="@color/bt_confirm"
+        android:strokeColor="@color/text_white_default"
         android:strokeLineCap="round" />
```

实现讲解：`api` 依赖会把 LinkApi 传递暴露给 Vlog 的所有使用方，扩大耦合面；确认无调用后直接删除。图标色用错资源是典型的"同名色值相近资源"手误，`bt_confirm`（按钮确认色）与白色在部分主题下接近，直到视觉走查才被发现。

## 复盘与要点
- 摘依赖时优先清理 `api` 级传递依赖，收益最大（编译隔离 + 包体）；此提交是 Launcher/AccountCenter/BTMusic/Vlog 一整轮依赖清理的组成部分。
- 颜色资源命名应体现语义（`text_white_default`）而非用途混用，icon 引用文字色保持了主题联动能力，方向正确。

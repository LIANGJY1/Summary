# 无单号 · Vlog 公共库色值适配

- **提交**：`8fcdff7b` | 2026-06-30 | daizhecheng | Vlog | feature
- **关联单**：无

**类型**：纯资源微调提交（1 个 drawable XML，2 处色值替换）。

## 内容一句话
Vlog 应用红色圆角按钮 `btn_bg_radio18_red.xml` 的 pressed/normal 两态色从 Setting 模块私有的 `@color/setting_warning` 切到公共库 `@color/btn_bg_red_color`，解除 Vlog 对 Setting 资源的越界依赖，并纳入黑白模式语义色体系。

```diff
# application/Vlog/src/main/res/drawable/btn_bg_radio18_red.xml
-            <solid android:color="@color/setting_warning" />
+            <solid android:color="@color/btn_bg_red_color" />
```

**要点**：跨模块引用他人模块私有 color（`setting_warning` 属于 Setting）在组件化工程里是隐性耦合，资源重命名时就会编译报错；借主题适配把跨模块色引用收敛到公共库，是一次低成本解耦。

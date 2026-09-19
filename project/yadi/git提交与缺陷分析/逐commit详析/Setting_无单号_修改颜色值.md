# 无单号 · 修改颜色值

- **提交**：`6d442286` | 2026-07-01 | dufan | Setting | feature
- **关联单**：无

**类型**：资源引用微调提交（1 文件，+2/-3 行）。

## 内容一句话
热点设备列表 `HotspotAdapter` 的两处文字颜色从"按名字符串取色"（`ResourceUtils.getColor("text_default_color")`/`"text_secondary_color"`）改为 `R.color.text_default_default`/`text_default_press` 常量引用，并删除一行注释掉的地址展示代码。

```diff
# application/Setting/src/main/java/com/yadea/setting/ui/adapter/HotspotAdapter.kt
-                ResourceUtils.getColor("text_default_color")
+                ResourceUtils.getColor(R.color.text_default_default)
...
-                ResourceUtils.getColor("text_secondary_color")
+                ResourceUtils.getColor(R.color.text_default_press)
```

**要点**：`getColor(字符串名)` 是旧换肤体系（对照 7366e05d）的残留——运行时按名反射查找，改名即静默失败；切换到 R 常量后编译期可查，且语义色在夜间模式自动取值。与 `SkinSwitchCardView` 的迁移同属一条清理线，本提交是其最后一小片。

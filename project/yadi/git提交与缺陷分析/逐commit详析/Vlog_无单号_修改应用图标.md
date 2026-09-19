# 无单号 · 修改应用图标（Vlog 换新 logo）

- **提交**：`e1c12e56` | 2026-07-08 | daizhecheng | Vlog | feature
- **关联单**：无

## 需求/目标
Vlog 应用替换桌面图标为新的 Vlog logo。

## 实现结构
- 修改 `application/Vlog/src/main/AndroidManifest.xml`：application 节点 `android:icon` 从 `@drawable/ic_launcher` 改为 `@drawable/icon_vlog`。
- 新增 `application/Vlog/src/main/res/drawable-mdpi/icon_vlog.png`（二进制，12KB）。

资源型提交，无代码逻辑改动。

## 关键代码
```diff
--- a/application/Vlog/src/main/AndroidManifest.xml
@@ -56,7 +56,7 @@
         android:name="com.yadea.vlog.init.App"
         android:allowBackup="true"
         android:enableOnBackInvokedCallback="true"
-        android:icon="@drawable/ic_launcher"
+        android:icon="@drawable/icon_vlog"
```

实现讲解：Manifest 层 icon 引用切换 + 新 png 入库，一行配置即完成品牌图标更换。

## 复盘与要点
- 新图标只落在 `drawable-mdpi` 一个密度目录，车机大屏（高密度）会被系统拉伸，建议提供 `xxhdpi` 及以上或 WebP 自适应图标（`mipmap-anydpi-v26` + foreground/background 层）。
- 应用图标类资源应放 `mipmap` 目录（Launcher 会按密度筛选），放 `drawable` 在部分机型缩放策略下可能失真。
- 类型标注：纯资源替换（stat 如上），一句话即可。

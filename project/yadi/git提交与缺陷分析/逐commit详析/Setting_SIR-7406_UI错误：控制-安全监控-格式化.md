# SIR-7406 · 控制-安全监控-格式化，正在格式化界面与UI不一致
- **提交**：`ca505ba7` | 2026-09-05 | sgh | Setting/CommonTools | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
进入"控制-安全监控-格式化"触发格式化时，"正在格式化"加载弹窗的旋转加载图标颜色与 UI 设计稿不一致。

## 根因分析
加载弹窗布局 `component/CommonTools/src/main/res/layout/layout_loading.xml` 的 `iv_loading` 复用了通用加载图 `@drawable/ic_loading`。该矢量图的 8 根辐条使用主题色资源 `@color/text_white_default` 并以 `strokeAlpha` 做灰度渐变——颜色实际值依赖主题解析，在格式化 Loading 场景下解析出的色值与设计稿指定的灰阶不符（缺陷库定性为"UI颜色值不对"）。修复方式不是改旧图，而是新增一份按设计稿硬编码色值的专用矢量图 `ic_loading_dialog.xml`（同样 90x90 viewport、8 根辐条，fillColor 依次为 `#FFFFFFFF`、`#FFE6E6E6`、`#FFCCCCCC`、`#FF999999`、`#FF666666`、`#FF4C4C4C`、`#FF808080`、`#FFB3B3B3`，形成固定灰阶渐变），并把 `layout_loading.xml` 的 `iv_loading` 源图切换为 `@drawable/ic_loading_dialog`。

## 关键代码修改
改动文件：component/CommonTools/src/main/res/drawable/ic_loading_dialog.xml（新增）；component/CommonTools/src/main/res/layout/layout_loading.xml
```diff
--- component/CommonTools/src/main/res/drawable/ic_loading_dialog.xml（新增，节选）
+  <path
+      android:pathData="M41.25,7.5H45C47.071,7.5 48.75,9.179 48.75,11.25V26.25H45C42.929,26.25 41.25,24.571 41.25,22.5V7.5Z"
+      android:fillColor="#FFFFFFFF"/>
+  <path
+      android:pathData="M41.25,82.5H45C47.071,82.5 48.75,80.821 48.75,78.75V63.75H45C42.929,63.75 41.25,65.429 41.25,67.5V82.5Z"
+      android:fillColor="#FF999999"/>
--- component/CommonTools/src/main/res/layout/layout_loading.xml
         android:layout_width="90dp"
         android:layout_height="90dp"
-        android:src="@drawable/ic_loading" />
+        android:src="@drawable/ic_loading_dialog" />
```

## 为什么能修复
新图把设计稿的灰阶色值直接固化进 drawable，不再经过主题色解析，弹窗加载图标颜色与式样一致；旧 `ic_loading` 未被改动，其他引用场景不受影响。代价是仓库里出现两份几乎同形的加载图（颜色策略不同），后续若加载图形状调整需同步两处。

## 复盘与经验
- "图标颜色与 UI 不符"若源于主题色资源（`@color/...` + alpha），在跨主题/跨窗口场景极易翻车；对视觉有精确要求的关键图，用硬编码色值的专用 drawable 比共用主题色资源更可控。
- 修复共用资源被单场景"绑架"的问题时，新增专用资源并切换引用（而非原地改共用资源）能避免波及其他使用方，本例是标准做法。

# SIR-7004 · 蓝牙/连接页列表滚动条样式与 UI 不符

- **提交**：`9418a192` | 2026-09-02 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
黑夜模式下蓝牙连接页面列表右侧滚动条与 UI 设计不符，且与无线网络界面的滚动条长短、样式不一致。

## 根因分析
`dialog_bluetooth_child.xml` 与 `dialog_connect_child.xml` 的 RecyclerView 只声明了 `android:scrollbars="vertical"`，未指定 `android:scrollbarThumbVertical`，于是使用系统默认 thumb——矩形、无圆角、长度随内容/视口比例自动变化，与设计稿的短圆角条不符；各页面有的配了自定义 thumb、有的用默认值，造成跨界面不一致（缺陷库根因"滚动条非圆角"）。

## 关键代码修改
改动文件：drawable/bg_rv_scrollbar_thumb.xml（新增）、dialog_bluetooth_child.xml、dialog_connect_child.xml
```diff
// application/Setting/src/main/res/drawable/bg_rv_scrollbar_thumb.xml（新增）
+<shape xmlns:android="http://schemas.android.com/apk/res/android"
+    android:shape="rectangle">
+    <!-- 滚动条颜色 -->
+    <solid android:color="@color/icon_default_disabled" />
+    <corners android:radius="@dimen/dp_6" />
+    <size android:width="4dp" />
+</shape>
```
```diff
// application/Setting/src/main/res/layout/dialog_bluetooth_child.xml（dialog_connect_child.xml 同）
                 android:overScrollMode="never"
                 android:paddingEnd="@dimen/dp_12"
                 android:scrollbars="vertical"
+                android:scrollbarThumbVertical="@drawable/bg_rv_scrollbar_thumb"
```

## 为什么能修复
统一替换为 `bg_rv_scrollbar_thumb`（4dp 宽、6dp 圆角、`icon_default_disabled` 色），两个页面的滚动条观感一致且符合设计稿；颜色走 token，日夜间主题自动适配。风险极低；隐患是本仓库 RecyclerView 数量多，其他列表若仍用默认 thumb，还会再冒出同类单子，宜全局排查一次。

## 复盘与经验
- "与 XX 界面不一致"类缺陷的根源常是组件属性没抽公共样式；滚动条、分割线、按钮这类细碎视觉件应沉淀为统一 drawable/style。
- `android:scrollbars="vertical"` ≠ 可用样式，必须配 `scrollbarThumbVertical` 才可控；新页面模板里应内置该属性。
- 颜色引用 token（icon_default_disabled）让同一份 drawable 同时满足日夜模式，避免再补 -night 版本。

# SIR-7366 · 连接页面"本机名称"编辑框唤起的输入法位置错误
- **提交**：`f8a4e7a7` | 2026-09-03 | sgh | Setting（CommonTools 组件） | bugfix（UI/输入法属性）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置-连接页面点击"本机名称"弹出编辑弹窗时，输入法（IME）弹出的位置/形态错误（全屏抽取式面板遮挡编辑框），与设计交互不符。

## 根因分析
通用编辑弹窗布局 `dialog_edit.xml` 中的 `EditText` 未设置任何 `imeOptions`，车机横屏大屏环境下系统默认可能采用 extract/fullscreen 输入模式（`flagExtractUi`/全屏 IME），导致输入法以全屏抽取形态出现在错误位置，遮挡弹窗内容。缺陷库根因"未设置输入法属性"与 diff 完全对应。

## 关键代码修改
改动文件：`component/CommonTools/src/main/res/layout/dialog_edit.xml`

```diff
--- component/CommonTools/src/main/res/layout/dialog_edit.xml
             android:layout_weight="1"
             android:background="@null"
             android:gravity="center_vertical"
+            android:imeOptions="flagNoExtractUi|flagNoFullscreen"
             android:textColor="@color/text_default_default"
             android:maxLength="6"
```

## 为什么能修复
`flagNoExtractUi` 禁用横屏抽取式编辑区，`flagNoFullscreen` 禁止输入法全屏，使 IME 以普通停靠面板形式出现在编辑框下方，位置符合车机横屏交互设计。单行属性修改，无逻辑副作用；若项目内还有其他自定义编辑框未同步该属性，同类问题仍会复现（本单只改了 `dialog_edit.xml` 这一个公共布局）。

## 复盘与经验
- 车机/横屏 Android 设备上，`EditText` 必须显式声明 `imeOptions="flagNoExtractUi|flagNoFullscreen"`，否则系统可能回退到手机竖屏时代遗留的全屏抽取式 IME，位置错乱是典型表现；建议作为团队布局规范。
- 公共组件（CommonTools 的 dialog_edit）修改一处即可覆盖所有引用方，本单收益面大；反过来公共布局改动也需回归所有使用该弹窗的页面。
- 输入法形态问题优先查 `imeOptions`/`windowSoftInputMode` 两个属性，而非动弹窗代码。

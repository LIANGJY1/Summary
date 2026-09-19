# SIR-5773 · APP 唤醒输入法后输入状态位置错误（WLAN 密码框全屏输入法重叠）

- **提交**：`99b9e9ea` | 2026-08-11 | dufan | Setting | bugfix（配置修正类）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：搜狗输入法为公版，依赖各 App 适配 / sol：App 调用输入法时修改属性禁用全屏弹窗）

## 问题
WLAN 连接弹窗中点击密码输入框唤醒输入法后，输入状态（候选栏/提取区）显示位置错误，与对话框界面重叠。

## 根因分析
`dialog_wlan_pwd_edit.xml` 的密码 `EditText` 没有设置 `imeOptions`，输入法默认可能进入"全屏提取模式（fullscreen/extract UI）"——在横屏大屏车机上，公版搜狗输入法会把编辑区提取成全屏覆盖层，与 Setting 的弹窗界面叠在一起，视觉上就是"界面重叠、输入位置错误"。缺陷库把根因点得很清楚：搜狗输入法是公版不可改，**每个 App 必须自行通过 `imeOptions` 声明不适配全屏提取**，本应用的密码框漏配。

## 关键代码修改
改动文件：`application/Setting/src/main/res/layout/dialog_wlan_pwd_edit.xml`
```diff
// application/Setting/src/main/res/layout/dialog_wlan_pwd_edit.xml
             android:layout_height="match_parent"
             android:layout_weight="1"
             android:background="@null"
+            android:imeOptions="flagNoExtractUi|flagNoFullscreen"
             android:gravity="center_vertical"
             android:inputType="textPassword"
```

## 为什么能修复
`flagNoExtractUi` 禁止输入法提取编辑区为全屏 UI，`flagNoFullscreen` 禁止横屏全屏输入模式，输入法被强制以普通停靠面板形式弹出，不再覆盖/错位到弹窗上，输入焦点与候选栏位置恢复正常。单属性配置改动，零逻辑风险。注意点：同工程其它可编辑控件（搜索框、备注框等）若同样漏配 `imeOptions`，在公版输入法下会复现同类重叠，应全局排查统一补齐。

## 复盘与经验
- 车机横屏 + 公版输入法的组合下，所有 `EditText` 都应显式声明 `imeOptions="flagNoExtractUi|flagNoFullscreen"`，这是平台适配约定而非可选项。
- "只有某个输入框重叠"的 bug 优先 diff 该控件与正常控件的 XML 属性差异，输入法行为差异 90% 由 `imeOptions`/`inputType` 决定。
- 第三方组件（公版输入法）不可修改时，适配责任就落在调用方属性声明上，评审时应把输入相关属性当作必查项。

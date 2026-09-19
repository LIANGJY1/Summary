# SIR-7084 · 蓝牙提示信息弹窗与 UI 不符

- **提交**：`9692358f` | 2026-09-02 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
连接-蓝牙的"提示信息"弹窗内容排版与 UI 设计不符（项目符号列表换行后缩进错乱）。

## 根因分析
原实现复用通用 `SentinelDialog(title, content)`，把 5 条蓝牙说明用 `\n` 拼进一个 string（`bluetooth_info_content`）塞进单个 TextView。新版布局 `dialog_bluetooth_tip.xml` 头部注释一语道破根因："SentinelDialog 单 TextView 用 \n 换行后第二行不与首行文字对齐"——当某条说明过长自动折行时，折行文字会顶到项目符号"•"下方甚至行首，缩进结构全乱，与设计稿的悬挂缩进列表不符。

## 关键代码修改
改动文件：ConnectFragment.kt、BluetoothTipDialog.kt（新增）、dialog_bluetooth_tip.xml（新增，153 行）、values(-en)/strings.xml
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt
         mBinding.layoutBt.ivInfo.setOnFastClickListener {
-            SentinelDialog(
-                getString(R.string.bluetooth_info_title),
-                getString(R.string.bluetooth_info_content)
-            ).show(childFragmentManager, "BluetoothInfoDialog")
+            BluetoothTipDialog().show(childFragmentManager, "BluetoothInfoDialog")
         }
```
```diff
// application/Setting/src/main/res/layout/dialog_bluetooth_tip.xml（新增，5 条 bullet 每行独立容器）
+            <LinearLayout android:orientation="horizontal" ...>
+                <TextView android:text="•" android:layout_gravity="top" ... />
+                <TextView android:layout_weight="1" android:layout_marginStart="@dimen/dp_12"
+                          android:text="@string/bluetooth_info_tip_item1" ... />
+            </LinearLayout>
（item2..item5 同构，外层 NestedScrollView 可滚动）
```

## 为什么能修复
文案拆为 `bluetooth_info_tip_item1..5` 五条独立 string，每条配"圆点 TextView + 说明 TextView"的横向容器：说明文字折行时始终对齐首行文字（weight=1 占满剩余宽度），圆点固定列宽，实现标准悬挂缩进；NestedScrollView 保证内容超高可滚动，弹窗高度 `WRAP_CONTENT`。中英文 strings 同步更新，无英文回落隐患。副作用：新增一个 Dialog 类与布局，后续同类列表型提示应复用该结构而非再造。

## 复盘与经验
- "多行列表文案"绝不能靠 `\n` 拼进单 TextView：自动折行必然破坏缩进，这是排版类 bug 的固定来源。
- 悬挂缩进列表的正确布局范式：行容器（横向）= 固定符号 + weight(1) 正文；本仓库已有 `dialog_sentinel_tip.xml`、`dialog_bluetooth_tip.xml` 两个先例，可提炼为通用组件。
- 通用弹窗组件（SentinelDialog）的能力边界要在团队内明确：单段短文案用它，结构化内容一律定制布局。

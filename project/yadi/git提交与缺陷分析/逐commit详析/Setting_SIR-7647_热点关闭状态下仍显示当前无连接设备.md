# SIR-7647 · 热点已关闭时仍显示"当前无连接设备"
- **提交**：`817ee8d0` | 2026-09-07 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设
- **备注**：`ef031938`（同日）是其 cherry-pick 的相同改动，机制一致，不重复剖析。

## 问题
热点（WLAN AP）关闭后，弹窗界面仍然显示"当前无连接设备"提示，状态与实际不符。

## 根因分析
`HotspotDialogFragment.handleNoClients()` 里，清理完客户端列表后无条件执行 `mBinding.tvNoConnect.visibility = View.VISIBLE`。该方法由"无客户端"事件回调触发，但回调到达时热点可能已经被关闭（用户点了关闭开关后底层仍上报一次 noClients 事件）。代码里其他地方对 `tvNoConnect` 的隐藏只挂在 `WIFI_AP_STATE_DISABLED` 分支和关闭确认回调里，没有考虑"事件到达顺序"——即"无连接设备"这个文案只在"开关为开 && 无客户端"时才成立。此外 `dialog_connect_child.xml` 中 `tvNoConnect` 初始没有 `visibility="gone"`，首次进入若时序异常也会闪现。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt（3 处补隐藏）、application/Setting/src/main/res/layout/dialog_connect_child.xml（默认隐藏）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
@@ -90,6 +90,7 @@
         when (state) {
             WIFI_AP_STATE_DISABLED -> {
                 mBindingHeader.tvCount.visibility = View.GONE
+                mBinding.tvNoConnect.visibility = View.GONE
                 resetCancel(false)
             }
@@ -292,7 +295,7 @@
     private fun handleNoClients() {
         mConnectedClients.clear()
         mHotspotAdapter.setNewInstance(null)
-        mBinding.tvNoConnect.visibility = View.VISIBLE
+        mBinding.tvNoConnect.visibility = if (mBindingHeader.switchHotspot.isChecked) View.VISIBLE else View.GONE
         mBindingHeader.tvCount.text = getString(R.string.no_connect_device)
     }
--- application/Setting/src/main/res/layout/dialog_connect_child.xml
@@ -45,6 +45,7 @@
             android:text="@string/no_wlan"
+            android:visibility="gone"
             android:textColor="@color/text_default_press"
```

## 为什么能修复
核心是把"显示无连接设备"的前提条件收紧为 `switchHotspot.isChecked`：关闭热点后到达的 noClients 事件不再把文案刷出来；同时在 `WIFI_AP_STATE_DISABLED` 状态分支与两条关闭路径（弹确认框、延时 200ms 关闭）补齐 `tvNoConnect` 隐藏，保证任一路径关闭热点后提示都被清掉；布局默认 `gone` 消除首帧闪现。隐患：状态显示依赖开关控件的 isChecked 而非数据源状态，若开关状态与底层 AP 状态短暂不一致仍可能出错，但比原来的无条件显示已健壮得多。

## 复盘经验
- "空态文案"的显示条件必须同时绑定功能开关态和列表态，事件回调里做 UI 刷子要带状态判断。
- 关闭类操作存在异步多路径（确认弹窗/直接关闭/延时关闭）时，每个路径都要收尾清理 UI 状态，或统一收敛到一个状态机入口。
- 提示性 TextView 在布局中默认 `gone`、由逻辑显式控制，可避免初始化时序造成的闪现。

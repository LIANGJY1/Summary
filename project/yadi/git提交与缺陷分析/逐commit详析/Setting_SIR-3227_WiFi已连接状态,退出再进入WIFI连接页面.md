# SIR-3227 · WiFi 页面重进时先展示"当前无可用网络"数秒

- **提交**：`1e42fc52` | 2026-07-22 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：业务逻辑代码编写如此；方案：修改对应逻辑）

## 问题
WiFi 已连接状态下退出再进入 WiFi 连接页面，在扫描结果返回前的加载期，列表空白并显示"当前无可用网络"提示，数秒后才有数据。

## 根因分析
`WlanDialogFragment` 初始化与打开开关分支原来只 `mAdapter.addData(MultiAccessPoint(2, null, true))` 插入一条"其他网络"占位项，列表真正的内容要等 `onWifiListChanged`/扫描回调里重建；重进页面的头几秒缓存数据明明可从 `mWxWifiManagerI.wifiList` 立即取到（含 `isSaved` 的已保存网络），代码却不去读，导致空列表 + `tvNoConnect` 显示"当前无可用网络"。另外缓存态里 `state` 已过期的条目会带着"已连接"文案渲染在未连接项上，与真实状态不符。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/adapter/WlanAdapter.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/WlanDialogFragment.kt
         if (mWxWifiManagerI.isEnable) {
             mBindingHeader.sw.isChecked = true
-            mAdapter.addData(MultiAccessPoint(2, null, true))
+            setInitData()
         }
@@（新增方法：进入页面立即用缓存构建列表）
+    private fun setInitData(isOpen: Boolean = false){
+        val list = mutableListOf<MultiAccessPoint>()
+        val cacheList = mutableListOf<MultiAccessPoint>()
+        var isNotConnecting = false
+        mWxWifiManagerI.wifiList.forEach {
+            if (it.isSaved) {
+                if (it.state != NetworkInfo.State.UNKNOWN) {// NOSONAR
+                    isNotConnecting = true
+                }
+                cacheList.add(MultiAccessPoint(1, it))
+            }
+        }
+        if (isNotConnecting) {
+            mAdapter.setCurrentConnectSsid("")
+        }
+        if (cacheList.isNotEmpty()) {
+            list.add(MultiAccessPoint(0))
+            list.addAll(cacheList)
+            list.add(MultiAccessPoint(3))
+        }
+        list.add(MultiAccessPoint(2, null, true))
+        mBinding.tvNoConnect.visibility = View.GONE
+        mAdapter.setIsJustOpen(isOpen)
+        mAdapter.setList(list)
+    }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/adapter/WlanAdapter.kt
+            if (mIsJustOpen && TextUtils.equals(status, ResourceUtils.getString(R.string.wifi_display_status_connected))) {
+                status = ResourceUtils.getString(R.string.wifi_remembered)
+            }
```
（扫描回调 `onScanResults` 中补 `mAdapter.setIsJustOpen(false)`，真实结果到达后恢复原文案逻辑）

## 为什么能修复
进入页面（及手动开 WiFi）时立即读取 `wifiList` 缓存并构建"已保存网络 + 其他网络"完整列表，同时 `tvNoConnect` 置 GONE，用户从第一帧就能看到已保存/已连接网络，消除了扫描等待期的空白窗口；`mIsJustOpen` 标志把缓存期不可靠的"已连接"文案降级为"已保存"，避免展示过期连接态，扫描结果到达即恢复精确状态。顺带删除了未再使用的 `truncateSsidByBytes`。隐患很小：仅依赖 `wifiList` 缓存的时效性，若缓存本身过期，会在扫描回调后被纠正。

## 复盘与经验
- 列表页"先占位等异步"的写法，若有本地缓存可读，应先渲染缓存再等刷新——首屏空态文案（"无可用网络"）与"加载中"是两种语义，混用会把正常加载期渲染成错误态。
- 缓存数据里的连接状态字段天然有时效性，用它首屏渲染时应对"已连接"这类强状态做降级展示，等真实回调再升级。
- 空态提示的显隐控制不要只在一条路径里维护，初始化、开关切换、扫描回调多条路径都要显式设置。

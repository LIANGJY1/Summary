# SIR-6790 · 哨兵模式"家"未设置地址却显示选中
- **提交**：`89ea9050` | 2026-08-31 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
哨兵模式下"家/公司"地址未在地图中设置时，点击"家"跳转地图，未设置就返回车控车设页，"家"仍显示选中状态，与地图实际数据不符。

## 根因分析
`SafetyMonitorFragment` 有两类错误叠加。其一，低级笔误：点击回调里写的是 `SettingsUtils.getGSetting(GRAY_NOT_ENABLED_HOME, ...)`，本意是 `setGSetting`——只读不写，选中状态从未持久化，且原代码把翻转选中态放在"跳转地图"之前、也没有任何回读校验，跳去地图没设置地址再返回，UI 自然停留在已选态。其二，状态不同步：Fragment 没有在 `onResume`（从地图返回必然走）重新核对"选中"与"地图侧是否真的配置了地址"，视图状态与地图 SDK（`SdkManager...requestFavoriteInfo`）的真值脱节，这正是单据所说"未同步地图位置信息"。

## 关键代码修改
改动文件：SafetyMonitorFragment.kt、SafetyMonitorViewModel.kt
```diff
--- a/.../ui/fragment/SafetyMonitorFragment.kt
         mBinding.tvHome.setOnFastClickListener {
-            mBinding.tvHome.isSelected = !(mBinding.tvHome.isSelected)
-            SettingsUtils.getGSetting(          // 笔误：只读不写
+            mBinding.tvHome.isSelected = !mBinding.tvHome.isSelected
+            SettingsUtils.setGSetting(          // 改为写入
                 GRAY_NOT_ENABLED_HOME, if (mBinding.tvHome.isSelected) 1 else 0
             )
-            if (mBinding.tvHome.isSelected) {
-                mViewModel.gotoHomeOrCompanyPage(...)
-            }
+            mViewModel.gotoHomeOrCompanyPage(requireContext(), childFragmentManager, TYPE_HOME) {
+                syncAddressSelection()
+            }
...
+    override fun onResume() {
+        super.onResume()
+        syncAddressSelection()   // 从地图返回时复核
+    }
+
+    private fun syncAddressSelection() {
+        checks.forEach { (type, view) ->
+            if (view.isSelected) {
+                mViewModel.checkAddressSet(type) { isSet ->
+                    if (!isSet) {
+                        view.isSelected = false
+                        SettingsUtils.setGSetting(key, 0)
+                    }
+                }
+            }
+        }
+    }
```
```diff
--- a/.../viewmodel/SafetyMonitorViewModel.kt
+    fun checkAddressSet(type: Int, callback: (Boolean) -> Unit) {
+        SdkManager.getInstance().naviProtocol.requestFavoriteInfo(type,
+            object : IProtocolCallback<ProtocolBaseModel> {
+                override fun onSuccess(model: ProtocolBaseModel) {
+                    val favoriteInfo = model as FavoritePoiModel
+                    callback(favoriteInfo.protocolFavPoiInfos.isNotEmpty())
+                }
+                override fun onFail(...) { callback(false) }
+            })
+    }
```
`gotoPage` 的地址弹窗 `cancel()` 回调接入 `onCancel()`，取消设置时也触发同步。

## 为什么能修复
三层收敛：get→set 笔误修复后选中态可持久化；`onResume` 强制复核，覆盖"从地图返回"的所有路径；`checkAddressSet` 用地图 SDK 收藏夹数据（`protocolFavPoiInfos` 是否为空）作为真值源，未设置即回退选中态并清 GSetting。取消弹窗也走同步，闭环。隐患：每次 onResume 发起至多两轮 SDK 异步查询，回调到达前 UI 可能短暂不一致；回调持有了 view 引用，需确认 Fragment 存活期。

## 复盘与经验
- `getGSetting`/`setGSetting` 一字之差编译器不报错（都是合法调用），状态类"只读不写"笔误要用单元测试或封装状态写入 API 防范。
- 跨应用（设置页↔地图）共享状态，返回时必须以对端真值重新校验，UI 本地状态只能当缓存。
- "选中"这类双态控件的翻转应放在确认对端操作成功之后，或至少有回滚路径，先翻再跳是顺序反了。

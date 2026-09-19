# SIR-1516 · Vlog 连接中按钮文案多了三个点
- **提交**：`80f87a0c` | 2026-07-01 | daizhecheng | Vlog | bugfix
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车载vlog

## 问题
Vlog 相机连接流程中按钮显示"连接中…"，按 UI 稿此处不该带省略号（多了三个点），文案状态错乱。

## 根因分析
两个不同状态复用了同一个字符串资源 `btn_search_device`：`HomeActivity` 的搜索按钮和 `CameraPairedActivity` 的 `ScanDeviceEvent.START`（连接开始）分支都取它。该资源的值被写成了"连接中…"——语义上是"连接中"状态文案，却挂在"搜索设备"的资源名下。结果：连接进行中按钮显示"连接中…"，与加载动画（`ivLoading` + `AnimationUtils.show()`）叠加后，省略号与动画的动态点重复，UI 表现为"连接中后面多了三个点"；资源名与文案语义也不匹配。缺陷库归因"UI问题"，机制上就是资源复用导致的文案错误。

## 关键代码修改
改动文件：`application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt`、`application/Vlog/src/main/res/values/strings.xml`、`application/Vlog/src/main/res/values-en/strings.xml`
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt
@@ -82,7 +82,7 @@
                     EventStatus.START -> {
                         mBinding.ivLoading.post {
-                            mBinding.btnConnect.text = getString(R.string.btn_search_device)
+                            mBinding.btnConnect.text = getString(R.string.btn_connecting)
                             mBinding.ivLoading.visibility = View.VISIBLE
                             AnimationUtils.show()
                         }
--- a/application/Vlog/src/main/res/values/strings.xml
@@ -13,7 +13,8 @@
-    <string name="btn_search_device">连接中…</string>
+    <string name="btn_search_device">搜索中</string>
+    <string name="btn_connecting">连接中…</string>
```

## 为什么能修复
把语义拆开：`btn_search_device` 回归"搜索中"（`HomeActivity` 搜索按钮使用，不再带错误省略号），新增 `btn_connecting`="连接中…" 专供连接中状态。两个界面各取所需，文案与状态一一对应。改动极小；中英两份 strings 同步修改，避免只改默认语言导致英文环境复现。

## 复盘与经验
- 一个字符串资源被多个界面/状态复用时，改一处文案会牵连他处；状态文案应按语义命名资源（btn_connecting/btn_search_device）而不是复用近义资源。
- "多了三个点"这类细节问题，根因常是资源值与资源名语义不一致，检索资源名全量引用点即可快速定位。

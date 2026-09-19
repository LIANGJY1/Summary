# 无单号 · 添加语音操作逻辑

- **提交**：`4448373d` | 2026-07-08 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
让语音助手能遥控设置应用：支持"跳转到指定设置页（驾驶/灯光/智驾/连接页等 13 个指令）"、"跳转并自动弹出 WiFi/蓝牙弹窗"、"语音关闭已打开的连接弹窗"、"语音关闭设置应用"四类操作。

## 实现结构
改动 3 个文件：
- 新增 `utils/VoiceOperationUtil.kt`（单例，143 行）：
  - `handleIntent()`：解析 Intent 中的 `key`（跳转大类）或 `voice_operation`（具体指令），映射到目标 Fragment，找到其在导航列表中的位置后回调 `setPage` 切页；带 `type`/wifi/蓝牙指令时延迟 600ms 弹出对应弹窗。
  - 动态广播 `com.yadea.setting.voice.close_interface`：收到 `close_connect_wifi/bluetooth` 时经 `MutableLiveData` 通知；其余指令直接 `mActivity?.finish()` 关闭应用。
- `ui/activity/MainActivity.kt`：删掉原 `handleIntent/findFragmentPosition` 与跳转常量，迁入工具类；`initView` 与 `onNewIntent` 统一改为调 `VoiceOperationUtil.handleIntent`；`onResume/onPause` 注册/注销语音广播。
- `ui/fragment/ConnectFragment.kt`：观察 `getCloseConnectDialog()` 的 LiveData，按指令 `safeDismiss()` WLAN 或蓝牙弹窗。

数据流：语音服务发 Intent（静态跳转）或广播（关闭类）→ `VoiceOperationUtil` 解析 → `MainActivity.setPage` 切页 / `ConnectFragment` 关弹窗 / Activity finish。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/utils/VoiceOperationUtil.kt（新增，节选）
+    private val voiceOperationReceiver by lazy {
+        object : BroadcastReceiver() {
+            override fun onReceive(context: Context?, intent: Intent) {
+                val operation = intent.getStringExtra("voice_operation")
+                when (operation) {
+                    "close_connect_wifi", "close_connect_bluetooth" -> {
+                        mCloseConnectDialog.postValue(operation)
+                    }
+                    else -> mActivity?.finish()
+                }
+            }
+        }
+    }
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt
@@ -62,6 +63,16 @@
+        VoiceOperationUtil.getCloseConnectDialog().observe(viewLifecycleOwner){
+            when (it) {
+                "close_connect_wifi" -> {
+                    mWlanDialogFragment?.safeDismiss()
+                }
+                "close_connect_bluetooth" -> {
+                    mBluetoothDialogFragment?.safeDismiss()
+                }
+            }
+        }
```
实现讲解：抽工具类 + LiveData 解耦是本提交的核心手法——"关弹窗"这类跨 Activity/Fragment 的命令不用再持有引用层层传递，而是广播 → 单例 LiveData → 观察者自取；切页逻辑复用既有 `findFragmentPosition`，把原 `MainActivity` 内联的 when 迁到工具类并扩充指令表。`onResume/onPause` 成对注册注销广播，避免静态 receiver 泄漏 Activity。

## 复盘与要点
- 语音指令用裸字符串常量散落 when 分支，建议后续收敛为 enum/常量表；新增页面指令只需在映射表加一行，扩展成本低，这是可复用的"指令→Fragment"注册表模式。
- 弹窗前延迟 600ms 等页面就绪是时序兜底（`ThreadUtils.runOnUiThreadDelayed`），在低端车机上仍偶发竞态，更稳的做法是观察页面首帧事件再弹。
- 遗留风险：`VoiceOperationUtil` 持有 `mActivity` 静态引用（已加 `@SuppressLint("StaticFieldLeak")`），且 `handleIntent` 中 `index` 可能为 -1 时仍访问 `navItemList[index]`（延迟回调里），存在越界隐患。

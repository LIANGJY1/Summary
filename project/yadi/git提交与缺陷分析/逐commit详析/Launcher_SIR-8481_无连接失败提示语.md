# SIR-8481 · HiCar 连接失败无失败提示语

- **提交**：`38450652` | 2026-09-16 | dufan | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
HiCar 连接失败后，界面没有出现任何"连接失败"提示语，用户不知道失败原因。

## 根因分析
`DeviceConnectManager` 处理 `HiCarConstants.FusionUiType.CONNECT_FAIL` 时，传给 `LinkActivity` 的是布尔量 `is_connect_fail=true`；而 `LinkActivity` 中 `mIsConnectFail` 为真时固定调 `setViewVisibility(3)`。视图状态 3 是"连接初始化失败"（`fail_hint`：连接初始化失败）的展示分支，且提示语义与 HiCar 场景不符——真正想要的"连接 XX 失败"文案根本没有对应分支，导致 HiCar 失败时提示语缺失/错误。修复把失败原因从布尔升级为 `connect_fail_type`（int，4=HiCar 连接失败），并新增视图分支 4：显示 `connect_fail_hint`（"连接%s失败,您可以尝试以下方案恢复"）并带设备名；广播回调里 CarLink 的 CONNECT_FAIL 分支同步从 3 改为 4。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt、application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt、application/Launcher/src/main/res/values/strings.xml、application/Launcher/src/main/res/values-en/strings.xml
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/DeviceConnectManager.kt
             HiCarConstants.FusionUiType.CONNECT_FAIL -> {
                 Intent(mContext, LinkActivity::class.java).apply {
                     putExtra("is_car_link", false)
-                    putExtra("is_connect_fail", true)
+                    putExtra("connect_fail_type", 4)
+                    try {
+                        putExtra("device_name", JSONObject(p1).getString("name"))
+                    } catch (e: JSONException) {
+                        e.printStackTrace()
+                    }
                     setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                     mContext!!.startActivity(this)
                 }
--- application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt
-    private val mIsConnectFail by lazy { intent.getBooleanExtra("is_connect_fail", false) }
+    private val mConnectFailType by lazy { intent.getIntExtra("connect_fail_type", 0) }
+    private var mIsConnectFail = false
@@ onCreate
+        mIsConnectFail = mConnectFailType != 0
         if (mIsConnectFail) {
-            setViewVisibility(3)
+            setViewVisibility(mConnectFailType)
         }
@@ setViewVisibility 新增分支
+            4 -> {
+                mBinding.llRetry.visibility = View.VISIBLE
+                val deviceName = intent.getStringExtra("device_name") ?: ""
+                mBinding.tvFailHint.text = String.format(getString(R.string.connect_fail_hint), deviceName)
+            }
```

## 为什么能修复
失败提示从"一个布尔 + 硬编码分支 3"扩展为"类型码 + 专用分支 4"，HiCar 连接失败有专属文案（含设备名）且重试按钮（`llRetry`）可见，提示语缺失问题消除。隐患：`JSONObject(p1).getString("name")` 依赖上游 JSON 里一定有 `name` 字段，异常时 `device_name` 为空串，文案会变成"连接失败"（缺名字）而非崩溃，可接受但值得注意。

## 复盘与经验
- 用布尔 extra 表达"失败与否"扛不住多类型失败场景，一开始就该用失败类型枚举/int 码传参——本次重构等于补了当初欠下的设计。
- 带设备名的失败文案（"连接%s失败"）显著降低用户困惑，上游事件 payload 里的字段要尽量透传。
- `setViewVisibility(int)` 这类魔法数字分支建议尽早改为密封类型/常量，避免 3/4 含义漂移。

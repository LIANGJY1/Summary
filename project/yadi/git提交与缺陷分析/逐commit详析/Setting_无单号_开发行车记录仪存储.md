# 无单号 · 开发行车记录仪存储

- **提交**：`31571beb` | 2026-08-28 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
把"安全监测"页的行车记录仪模块从本地假实现（SettingsUtils 存布尔 + 写死的存储容量常量）升级为真实接入 DVR 本地服务：开关/录音/水印/录像时长直控 DVR 服务，U 盘容量与各分区占用实时展示，支持格式化。

## 实现结构
- `build.gradle` + `libs/DvrLocalServiceManager-1.0.jar`：引入 DVR 本地服务管理 jar（`DvrLocalServiceMng` 单例）。
- `SafetyMonitorFragment.kt`（核心，+311 行）：
  - `initDvrService()` 建立 AIDL 连接并挂 `IDvrCallBackListener`；未连接时 `setDashcamGray(false)` 置灰全部控件。
  - 开关监听从写 SettingsUtils 改为调 `dvrManager.startRecord/stopRecord、micOffOn、timeStampEnable、videoLength`，并以 `suppressDashcamCheck` 标志防止程序化设置 isChecked 时误触发下发。
  - 服务回调 `refreshDvrStates()` 反向同步 UI；`onUpdateVideoDataSize` 按 VideoType 分账到循环/紧急/用户/哨兵四个占用变量；`notifyDeviceValue("usb_memory")` 解析 `10240_2048_5120_1024_65536_20480` 式下划线串（第 5 段总容量、第 6 段剩余）。
  - U 盘插拔用 `BroadcastReceiver`（MEDIA_MOUNTED/EJECT 等）监听，插入延迟 300ms 拉取容量，拔出清零重画存储条。
- `Constants.java`：删除写死的 STORAGE_*_GB 常量；strings 增加格式化提示等多语言文案；布局微调。

数据流：DVR 服务连接回调 → 置灰解除 + 状态回读；用户操作 → dvrManager 属性下发；DVR 容量回调 → 四分区变量 → `updateStorageBar()` 重画堆叠条 + 百分比文本。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
@@ -218,35 +218,35 @@
-            ssvLoopRecord.isChecked = SettingsUtils.getGSetting(DASHCAM_LOOP_RECORD, 0) == 1
             //循环录像
             ssvLoopRecord.switchCompat.setOnCheckedChangeListener { _, isChecked ->
+                if (suppressDashcamCheck) return@setOnCheckedChangeListener
                 log("dashcam loop record select: $isChecked")
-                SettingsUtils.setGSetting(DASHCAM_LOOP_RECORD, if (isChecked) 1 else 0)
+                if (isChecked) dvrManager.startRecord() else dvrManager.stopRecord()
             }
```
```diff
@@ -286,191 +286,191 @@
+        override fun onUpdateVideoDataSize(sizeKb: Long, type: Int) {
+            val usedMb = sizeKb / 1024
+            when (type) {
+                Constant.VideoType.CYCLE_RECORD -> usedLoopMb = usedMb
+                Constant.VideoType.EMERGENCY_SAVE -> usedEmergencyMb = usedMb
+                Constant.VideoType.PICTURE -> usedUserMb = usedMb
+                Constant.VideoType.DRIVING_ASSISTANT_RECODE -> usedSentryMb = usedMb
+                else -> return
+            }
+            updateStorageBar()
+        }
```
```diff
+    private val usbMediaReceiver = object : BroadcastReceiver() {
+        override fun onReceive(context: Context?, intent: Intent?) {
+            val action = intent?.action ?: return
+            when (action) {
+                Intent.ACTION_MEDIA_MOUNTED -> {
+                    // U盘插入
+                    mBinding.root.postDelayed({ refreshUsbMemory() }, 300)
+                }
+                Intent.ACTION_MEDIA_UNMOUNTED,
+                Intent.ACTION_MEDIA_REMOVED,
+                Intent.ACTION_MEDIA_EJECT -> {
+                    // U盘拔出
+                    clearUsbMemory()
+                }
+            }
+        }
+    }
```

实现讲解：手法的核心是"双向同步防回环"——程序化 `isChecked=` 会触发 listener 造成误下发，本提交用 `suppressDashcamCheck` 布尔闸门统一拦住三个开关（回调内部还有更细的 setSwitchSuppress 保存/恢复现场版本）。存储展示从假数据换成事件驱动：分区占用靠服务增量回调、总容量靠字符串协议解析，插拔用系统广播兜底并在 onDestroyView 双 runCatching 释放 receiver 与 callback。

## 复盘与要点
- "假数据 UI 先行、服务后接"的迁移可借鉴：本提交几乎只改数据来源，UI 结构未动，说明前期用 Constants 常量画存储条是有效的过渡策略。
- 回环抑制标志是控件双向绑定的通用痛点，比逐个 `checkChangeListener.enable=false` 更集中；但要保证所有出口都复位（本实现 refreshDvrStates 用 try/finally 语义上更稳，当前写法在异常时会泄漏标志）。
- 遗留风险：`parseUsbMemory` 依赖下划线分隔的位置协议，段数/单位变化即失效，好在有 runCatching 与日志；`formatStorageText` 的 else 分支 `String.format("--")` 参数无占位符，属小瑕疵。

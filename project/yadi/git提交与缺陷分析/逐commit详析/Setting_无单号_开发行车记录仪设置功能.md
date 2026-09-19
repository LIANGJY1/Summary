# 无单号 [SRS_SYSDVRSetting_001] 开发行车记录仪设置功能
- **提交**：`f87bb915` | 2026-08-20 | sgh | Setting | feature
- **关联单**：SRS_SYSDVRSetting_001

## 需求/目标
安全监控页（SafetyMonitorFragment）新增行车记录仪设置区块：循环录像/录音/驾驶信息水印三个开关、录像时长档位、存储空间占比条与"格式化"入口（本期全部本地占位，记录仪服务接口待接）。

## 实现结构
- `Constants.java`：新增 4 个 DASHCAM_* 本地存储 key 与 5 个 STORAGE_*_GB 容量常量（当前为硬编码占位值：总 128G、紧急录像 32G、用户相册 32G 等）。
- `SafetyMonitorFragment.kt`（+73 行）：三个开关走 `SettingsUtils.getGSetting/setGSetting` 读写（与"解除屏幕锁定"同一模式，不走 CAN）；录像时长 RadioGroup 本地记忆；`initStorageSpaceUI` 用常量组装分段占比数据。
- `widget/StorageUsageBarView.kt`（新增，81 行）：自绘分段存储条——先画整条圆角轨道，再逐段画圆角矩形（仅首段左圆角、末段右圆角，`path.addRoundRect` 8 分量圆角数组实现），0 值段跳过。
- `fragment_safety_monitor.xml`（+205 行）+ 4 个分段色 shape + 中英文案 51 条。
- 数据流：UI ↔ SettingsUtils 本地 KV；存储条数据 ← Constants 静态常量（暂无真实容量查询）；格式化按钮 TODO。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/widget/StorageUsageBarView.kt
+        //只绘制占用段，剩余空间不绘制，直接露出轨道背景色
+        val first = sizes.indexOfFirst { it > 0f }
+        val lastUsage = sizes.indexOfLast { it > 0f }
+        var left = 0f
+        for (i in sizes.indices) {
+            val s = sizes[i]
+            if (s <= 0f) continue
+            val segW = w * s / total
+            //第一个和最后一个画圆角
+            val lr = if (i == first) cornerRadius else 0f
+            val rr = if (i == lastUsage) cornerRadius else 0f
+            rectF.set(left, 0f, left + segW, h)
+            path.reset()
+            path.addRoundRect(rectF,
+                floatArrayOf(lr, lr, rr, rr, rr, rr, lr, lr), Path.Direction.CW)
+            paint.color = colors[i]
+            canvas.drawPath(path, paint)
+            left += segW
+        }
```
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
+            ssvLoopRecord.isChecked = SettingsUtils.getGSetting(DASHCAM_LOOP_RECORD, 0) == 1
+            ssvLoopRecord.switchCompat.setOnCheckedChangeListener { _, isChecked ->
+                SettingsUtils.setGSetting(DASHCAM_LOOP_RECORD, if (isChecked) 1 else 0)
+            }
+        //格式化存储设备
+        mBinding.btnFormat.setOnFastClickListener {
+            log("format storage device click")
+            //TODO 接入格式化存储设备内部接口
+        }
```
StorageUsageBarView 用"跳过 0 段 + 首尾段才圆角"的细节保证分段条在部分段为 0 时视觉仍是完整圆角胶囊，81 行完成一个可复用组件。

## 复盘与要点
- 记录仪设置整块采用"UI 先行 + 本地 KV + 常量占位 + TODO 接口"的四步灰度，结构清晰但要求 TODO 有跟踪出口（a966382d 的格式化弹窗是后续闭环）。
- 存储容量写死在 Constants（STORAGE_*_GB）而非实时查询，演示阶段可用，接入真实数据前必须替换，否则 UI 永远显示 50%。
- 分段进度条组件与业务解耦（setData 数组入参），可直接沉淀到 common 控件库。

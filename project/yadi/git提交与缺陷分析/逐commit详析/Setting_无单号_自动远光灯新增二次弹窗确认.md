# 无单号 · 自动远光灯开启新增二次弹窗确认

- **提交**：`f0ebfcaf` | 2026-07-27 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
自动远光灯（IHC）从"点击开关直接生效"改为"开启需二次弹窗确认（含'外部灯光需调到 AUTO'提示），关闭仍然直发"；并配套实现 1 秒无回显自动回弹的防呆逻辑，顺带移除灯光/车控页的滚动渐隐遮罩。

## 实现结构
5 个文件（+92/-49）：
- `ui/fragment/LightFragment.kt`：
  - `setAutoHighBeamListener`：点击回调只处理"关"（直接发 SWITCH_OFF），"开"路径移到 overlay 点击回调——`ssvAutoHighBeam.setOnOverlayClickListener` 中弹 `showWarningDialog`（标题/内容用新文案，内容即 `24f1277f` 预埋的 `light_open_auto_tip`），确认后置状态 + `isChecked=true` + 发 SWITCH_ON + `startReboundForSwitch`；
  - 新增 `reboundJobs: MutableMap<CustomSwitchCompat, Job>` 与 `startReboundForSwitch/cancelReboundForSwitch`：1 秒内服务端未回显则把开关弹回 OFF；`onPause` 统一取消；
  - `updateAutoHighBeamUI`：`autoHighBeamStateTemp` 改为可空比较，回显一致时取消回弹并清空待确认状态；开关开启且回显成功时 `disableOverlay`，否则 `enableOverlay`（保持可再次点击弹窗）；
  - `initView` 删除 `setupScrollFade`，`updateSeatControlEnabledState` 同型的 enabled 逻辑同步 overlay 开闭；
- 布局 `fragment_light_adjustment.xml/fragment_vehicle_control.xml`：删除顶部/底部渐隐遮罩 View（与代码删除对应）；`strings.xml` 中英文补弹窗标题/确认/取消文案。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/LightFragment.kt
+            ssvAutoHighBeam.setOnOverlayClickListener {
+                if (!isAdasControlEnabled) {
+                    return@setOnOverlayClickListener
+                }
+                //打开是二次弹窗确认
+                showWarningDialog(
+                    title = getString(R.string.auto_high_beam_open_title),
+                    content = getString(R.string.auto_high_beam_open_content),
+                    onConfirm = {
+                        autoHighBeamStateTemp = true
+                        mBinding.ssvAutoHighBeam.isChecked = true
+                        settingVehicleService.sendL2A(CarPropertyIds.IHC_SWITCH, CanSignalConstants.SWITCH_ON)
+                        startReboundForSwitch(mBinding.ssvAutoHighBeam.switchCompat)
+                    }
+                )
+            }
```
```diff
+    private fun startReboundForSwitch(view: CustomSwitchCompat) {
+        reboundJobs[view]?.cancel()
+        val job = viewLifecycleOwner.lifecycleScope.launch {
+            delay(REBOUND_DELAY_MS)
+            if (isActive && isVisible && view.isAttachedToWindow) {
+                view.isChecked = false
+            }
+        }
+        reboundJobs[view] = job
+    }
```
实现讲解：用开关组件的 overlay 层拦截"开"的触摸，弹确认框后才真正拨开关并下发信号；发出后挂 1 秒回弹 Job，服务端回显（`updateAutoHighBeamUI`）会取消它——形成"乐观更新 + 超时回滚 + 回显确认"的完整闭环，注释说明是参考辅助驾驶前向碰撞预警（FCW）的成熟模式。

## 复盘与要点
- 安全相关功能（自动远光涉及行车安全法规）加二次确认是合规常见要求；"overlay 拦截 + 弹窗 + 回弹"三件套已在 FCW/此处两处落地，值得抽成通用组件而非第三次复制。
- `autoHighBeamStateTemp` 从 Boolean 变"可空三态"（null=无待确认值）与 `1becea20` 的 `Boolean?` 手法一致，同一作者的风格正在统一。
- 遗留风险：回弹 1 秒窗口依赖 L2A 回显及时；弹窗弹出期间开关状态未变（仍是 OFF），用户确认后立即开启——若 MCU 实际拒绝，靠回弹兜底，但无失败 toast（对比 `24f1277f` 加热有提示），提示可补齐。

# SIR-7795/7866/7870/7906 · D档行驶中仍可操作音量OSD & 通话OSD降到最低样式不符 & 三指焦点误判 & CP通话主题不跟随
- **提交**：`553d56b4` | 2026-09-08 | ljl | SystemUI（含 BTPhone） | bugfix
- **缺陷库**：SIR-7795 C·必现·主交互（需求变更）；SIR-7866 C·必现·主交互（UI 调整）；SIR-7870 C·高概率·地图导航（焦点 firstOrNull 缺陷）；SIR-7906 C·必现·手车互联（未主动切换主题）

## 问题
四个关联问题一次合修：① D 档非 0 车速下音量/通话 OSD 条仍可触摸调节、可展开多条音量；② 通话 OSD 降到最低时 UI 与设计不符（最低应为 5 且非静音态）；③ 导航/媒体焦点高频交替时三指操作误识别为媒体（音量 OSD 弹错组）；④ CarPlay 通话弹窗日夜切换后仍是旧主题配色。

## 根因分析
① 行驶锁定判定散落在 `VolumeDialogActor` 自维护的 `curSpeed/curGear` 字段上（`setSpeed/setGear` 手动同步），信号更新不及时或未覆盖全部入口（单条触摸、多条触摸、展开按钮）导致 D 档非 0 车速仍可操作。② 通话音量显示范围映射错误：`showVolume` 中 `mMinVolume = if (isCallVolume) 0 else minVolume` 把 UI 显示下限固定为 0，而系统通话实际可调下限是 5（`CALL_MIN_VOLUME_LIMIT`），拖到最低条形位置与 UI 稿不符，且 `mIsMute = targetVolume <= mMinVolume` 在限 5 时误判静音。③ `CarAudioVolumeController.carFocusCallback`（原 212 行）与 `getCarAudioFocusGroupId()` 都用 `focusHolders?.firstOrNull()` 取焦点——多持有者并存（log 中 size=2/3 常见）时不保证是正在发声的导航；触发三指瞬间"当前焦点持有者"恰好在媒体窗口期（导航焦点两次到达间隔可达 20 秒），三指全部被识别为媒体。④ `CarPlayCallWindow.ensureView()` 缓存 `mRootView`，overlay 视图按 inflate 当时主题解析资源，日夜切换后缓存不刷新，白天弹出夜间黑框。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt；application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt；application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java；application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallManager.java；application/BTPhone/src/main/java/com/yadea/btphone/BtPhoneApp.java；另有 digitalkey/KeyguardActor 同步改动
```diff
--- application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/VolumeDialogActor.kt
@@ -195,8 +185,8 @@
         mSingleView.findViewById<View>(R.id.fl_expand_arrow_container)?.setOnClickListener {
-            //添加档位和车速判断，仅在车辆档位为P档 或 档位为D/R档但车速为0的情况可以展开多条音量
-            if (curGear == Gear.P || curSpeed <= 0) {
+            //行驶触屏锁定状态（车速+开关统一判定，SIR-7795）：锁定期间禁止展开多音量条
+            if (!DriveTouchLockController.isLocked()) {
                 expandToMultiBars()
             }
```
```diff
--- application/SystemUI/src/main/java/com/android/systemui/vehiclecontrol/volume/CarAudioVolumeController.kt
@@ -210,28 +224,22 @@
         override fun onCarFocusChanged(audioZoneId: Int, focusHolders: List<AudioFocusInfo?>?) {
             try {
-                val focusInfo = focusHolders?.firstOrNull()
+                val holders = focusHolders?.filterNotNull() ?: emptyList()
+                // SIR-7870：与 getCarAudioFocusGroupId 同策略按优先级看焦点
+                val focusInfo = holders.minByOrNull { focusPriority(it.attributes.usage) }
```
（同文件新增 `focusPriority(usage)`：通话=0 > 导航引导=1 > 语音助理=2 > 其他=3；新增 `refreshOsdIfShowing(groupId)` 同步无 FLAG_SHOW_UI 的音量变化。）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java
@@ -140,12 +143,20 @@
     private void ensureView() {
+        int currentNightMode = currentNightMode();
+        if (mRootView != null && currentNightMode != mInflatedNightMode) {
+            // 日夜模式已切换：缓存视图里的 Drawable 是按旧主题解析的，必须丢弃重建（SIR-7906/7907）
+            dropCachedView();
+        }
         if (mRootView != null) {
             return;
         }
```
通话 OSD 侧：`showVolume`/`updateBarItem` 中通话显示下限改为 `minVolume.coerceAtLeast(CALL_MIN_VOLUME_LIMIT)`（5~max 映射），静音判据统一为 `isDisplayMute(...)`（通话按 volume<=0）；单条/多条 OSD 的 DOWN/MOVE/UP 全部加 `DriveTouchLockController.isLocked()` 短路。CP 侧：`CarPlayCallManager.onDayNightChanged()` 调 `dropCachedView()+refreshWindow()`，`BtPhoneApp.applyThemeBasedOnUiMode` 日夜切换时调用。

## 为什么能修复
① 行驶锁定收敛到 `DriveTouchLockController.isLocked()` 单一判定源（车速+开关统一），并覆盖全部触摸/展开入口，消除信号同步时差；② 显示区间 5~max 映射后最低位置与 UI 稿一致、不再误显静音；③ 焦点选取从 firstOrNull 改为 usage 优先级 minByOrNull，导航发声期即使多持有者并存也能选中导航组，同时媒体包名跟踪改为"列表中第一个媒体组持有者"+800ms 防抖，避免瞬时抢占丢音源；④ 缓存视图按 uiMode 夜间位过期检查+主动丢弃重建，日夜切换后弹窗主题即时跟随。副作用：焦点优先级是产品语义约定，后续新增 usage 需同步优先级表；`refreshOsdIfShowing` 让 OSD 在设置页调节时跟随切换 group，行为需回归。注：本提交还捆绑了 digitalkey 锁门控（`isLockoutGatingActive`）与 KeyguardActor 较大改动，与标题四个单号无直接关系，属顺带提交。

## 复盘与经验
- 车速/档位类行驶安全判定必须收敛为单一状态源（如 DriveTouchLockController），各 UI 入口只查状态；散落的 setSpeed/setGear 字段同步必然出现时差和漏覆盖。
- `firstOrNull()` 取"当前焦点持有者"在多焦点并存场景是错误假设，应按业务优先级（通话>导航>语音>媒体）显式选取。
- overlay/常驻缓存的视图资源不随 uiMode 自动刷新，日夜主题跟随需要在配置变化时主动丢弃重建，inflate 时记录主题指纹（uiMode 位）做过期检查是通用模式。
- 一个提交合修多个单号时，标题逐条列明 issue，且应避免捆绑无关模块改动（本例 digitalkey/Keyguard），否则回归范围难以界定。

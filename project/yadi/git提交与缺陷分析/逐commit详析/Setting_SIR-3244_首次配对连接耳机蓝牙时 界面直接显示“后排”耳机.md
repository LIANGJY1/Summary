# SIR-3244 · 首次配对耳机被直接显示为“后排”（蓝牙角色role赋值与切换链路混乱）

- **提交**：`f0c04f64` | 2026-07-24 | daizhecheng | Setting | bugfix（大重构：同步 D1 分支代码）
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
首次配对连接耳机蓝牙时，设备列表直接把该耳机显示为"后排"耳机，角色标注错误。

## 根因分析
列表的角色标签由 `DeviceBean.role` 决定（diff 中映射为 `role==0 → back_row 后排`、`role==1 → front_row 前排`），而旧代码的 role 生命周期不严谨：首次配对的设备 role 未经过明确的分配/切换流程就可能带着默认值 0 被 UI 渲染；`setRole()` 虽然会同步本地 `mPairedDevices` 并 `saveDeviceList` 持久化，但"换机连接/角色切换"的完整时序（先断开对方、切换角色、复位标志位、再重连）没有成型的流程保障，`BluetoothAnwFragment.handleSwitchClick` 里的旧实现松散且不经 BtAnwManager 统一驱动。提交 [how]"同步D1分支代码"表明这是把 D1 分支上已修好的角色切换体系整体同步过来。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt；application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt；component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java；application/Setting/src/main/java/com/yadea/setting/ui/fragment/SoundFragment.kt（仅日志）
```diff
--- a/.../adapter/BluetoothAnwAdapter.kt（角色标签与图标按显式参数渲染）
-        setupBondedDeviceView(holder, this, major)
+        bindBondedDeviceItem(holder, major, role, isConnect)
@@
-        if (device.role == 0 || device.role == 1) {
-            holder.setText(R.id.tv_seat, if (device.role == 0) R.string.back_row else R.string.front_row)
+        if (role == 0 || role == 1) {
+            holder.setText(R.id.tv_seat, if (role == 0) R.string.back_row else R.string.front_row)
         }
--- a/.../diologfragment/BluetoothAnwFragment.kt（切换流程重写）
                 lifecycleScope.launch(ioDispatcher) {
                     var frontDevice: DeviceBean? = null
                     var rearDevice: DeviceBean? = null
                     BtAnwManager.getInstance().mPairedDevices.forEach {
                         if (it.role == 1) frontDevice = it
                         else if (it.role == 0) rearDevice = it
                     }
                     val otherDevice = if (clickedDevice.role == 1) rearDevice else frontDevice
                     ...
                     disconnectDevice(otherDevice)
                     executeRoleSwitch(otherDevice, clickedDevice)
                     delay(2000.milliseconds)
                     resetDeviceFlags(resetDevice)
                     otherDevice?.let {
                         BtAnwManager.getInstance().connectHfp(it, true)
                         BtAnwManager.getInstance().connectA2dp(it, true)
                     }
                 }
--- a/.../anwBt/BtAnwManager.java（新增 AVRC P/HFP 角色联动）
+    public void switchAvrcpAndHfp() {
+        for (DeviceBean device : mPairedDevices) {
+            if (isConnect(device)) { connectAvrcp(device, device.role); }
+            else { disconnectAvrcp(device, device.role); }
+        }
+    }
+    // role==1（前排）额外联动 setVoiceRecognitionNew；旧 setAvrcpControl/setVoiceRecognition 标记 Deprecated
@@ AVRCP 音源分配后
                 mBtAdapter.AnWBT_AVRCP_AudioSourceIdSet(address, availableAudioSourceId);
+                switchAvrcpAndHfp();
```

## 为什么能修复
① UI 渲染改为从点击项实时取 `role/isConnect` 显式传参，图标四态内联展开，避免旧封装里 role 未初始化时的错误映射；② 角色切换固化为"断开对方 → `executeRoleSwitch` → 延时 2s → `resetDeviceFlags` → 重连 HFP/A2DP"的标准时序，role 的变更与持久化（`setRole` → `saveDeviceList`）有了唯一入口；③ `switchAvrcpAndHfp` 让 AVRCP 控制/语音识别随角色与连接态联动，audio source 分配后同步刷新。整体上首次配对设备的角色从"被动默认值"变成"经过切换流程的显式状态"。说明：本提交为分支级同步重构，diff 无法逐行对应到"首次配对显示后排"的单点病灶，以上按实际改动归纳；`SoundFragment` 仅日志增强。

## 复盘与经验
- 角色/主备这类有业务语义的字段，必须有唯一的分配入口和固化时序（断开→切换→复位→重连），散落在 UI 回调里的切换步骤迟早出显示错乱。
- "同步某分支代码"式修复在复盘时可读性差：同步前先提炼对方分支的关键改动点写进提交说明，方便回溯。
- 渲染层直接读 `device.role` 这类可变字段，不如在 bind 时显式传参——既便于测试，也让数据来源一目了然。

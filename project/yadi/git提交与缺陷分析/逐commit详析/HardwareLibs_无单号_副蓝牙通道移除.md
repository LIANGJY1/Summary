# SIR-XXX · 副蓝牙通道移除

- **提交**：`cd685d82` | 2026-08-26 | dufan | HardwareLibs | feature（库能力收敛 + AIDL 扩展）
- **关联单**：SIR-XXX（占位单号）

## 需求/目标
Hardwarelibs 的 `BtAnwManager` 中"副蓝牙（双机角色）AVRCP/语音识别"通道整体拆除：废弃方法的实现体清空、调用链删除；同时在 `IAnwPhoneLink` AIDL 上补齐若干设备角色/连接管理接口声明。

## 实现结构
2 个文件、+8/-89：
- `IAnwPhoneLink.aidl`：新增 `AnWBT_DeviceIsConnectedByProfile`、`AnWBT_GetDeviceCurrentMode`、`AnWBT_DisconnectAndAcceptDevice`、`AnWBT_Set/Get_Default_Master_Device`、`AnWBT_Set_User_Disconnect_Flag`、`AnWBT_Set_Auto_Connect_Interrupt_Flag` 等声明（与底层服务新协议对齐，把"副蓝牙切换"下沉到服务侧按默认主设备/断开让位策略管理）；
- `BtAnwManager.java`：删除 `setAvrcpControl`（本已废弃空壳）、`setAvrcpControlNew`、`setVoiceRecognition(New)`、`findAvailableAudioSourceId`，并清空 `updateSecondaryDeviceAvrcpAndVoice` 里按角色设置 AudioSourceId、AVRCP Control 的实现体。

## 关键代码
```diff
--- a/component/Hardwarelibs/src/main/java/com/anwExt/carui/bt/anwBt/BtAnwManager.java
-    public void setAvrcpControl(int controlId) {
-        Log.d(TAG, "Deprecated setAvrcpControl");
-//        ... 按角色遍历配对设备下发 AnWBT_AVRCP_Control 的旧逻辑（已注释）
-    }
-
-    public void setVoiceRecognition(int enable) {
-        Log.d(TAG, "Deprecated setVoiceRecognition -> enable=" + enable);
-        //   mBtAdapter.AnWBT_HFPAG_VoiceRecognition(enable);
-    }
```
```diff
--- a/component/Hardwarelibs/src/main/aidl/com/anwsdk/service/IAnwPhoneLink.aidl
+    int AnWBT_DeviceIsConnectedByProfile(String btaddr);
...
+	int AnWBT_DisconnectAndAcceptDevice(String disconnectBtaddr,String acceptBtaddr,int accept);
+	int AnWBT_Set_Default_Master_Device(String btaddr);
+	String AnWBT_Get_Default_Master_Device();
+	int AnWBT_Set_User_Disconnect_Flag(String btaddr,int flag);
+	int AnWBT_Set_Auto_Connect_Interrupt_Flag();
```
实现讲解：架构意图是"应用层角色仲裁 → 服务层仲裁"的迁移：原先由 BtAnwManager 在应用侧遍历配对设备、按 role=0/1 手动分配 AVRCP 音源并控制播放/语音识别；拆除后 AIDL 新增 `Set_Default_Master_Device`、`DisconnectAndAcceptDevice`、`Auto_Connect_Interrupt_Flag` 等原语，由蓝牙服务内部完成主副设备切换决策。应用侧 API（4bf0fa86）同日清空调用点，两端闭环。

## 复盘与要点
- 双蓝牙设备角色管理从 UI/应用层下沉服务层，是典型的"策略下沉"重构：减少多应用重复实现仲裁逻辑，但要求服务端协议一次到位（本次 AIDL 一次性扩了 7 个方法）。
- `setAvrcpControl` 空实现保留签名数日后才连签名一起删（后续提交），期间调用方编译不破——渐进拆除的兼容窗口设计可复用。
- 删除 `findAvailableAudioSourceId` 等未被引用的公共方法前应确认无外部模块反射/依赖。

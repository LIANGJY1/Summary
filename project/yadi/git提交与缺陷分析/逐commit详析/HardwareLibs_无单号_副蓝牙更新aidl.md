# SIR-XXX · 副蓝牙更新 aidl

- **提交**：`5eec7f11` | 2026-09-07 | dufan | HardwareLibs | feature（纯接口声明同步）
- **关联单**：SIR-XXX（占位单号）

## 需求/目标
副蓝牙（ANW）手机互联 AIDL 接口与底层服务版本对齐：新增麦克风开关控制接口 `AnWBT_SetMicEnable(int enable)` 的声明。

## 实现结构
单文件单行：`component/Hardwarelibs/src/main/aidl/com/anwsdk/service/IAnwPhoneLink.aidl` 在 `AnWBT_SetScanMode` 与 `AnWBT_GetScanMode` 之间插入 `int AnWBT_SetMicEnable(int enable);`。接口顺序与对端服务保持一致（AIDL 按 transaction 序号绑定方法顺序，插入位置须与底层服务端 .aidl 完全一致，否则跨版本调用错位）。

类型标注：纯 aidl 声明同步提交，无调用侧实现——后续业务（如通话静音、语音互斥）再基于此接口开发。

## 关键代码
```diff
--- a/component/Hardwarelibs/src/main/aidl/com/anwsdk/service/IAnwPhoneLink.aidl
@@ -28,6 +28,7 @@
     int AnWBT_SetLocalDevAddrOrName(int addr_or_name,String data);
     int AnWBT_SetScanMode(int scan_mode);
+    int AnWBT_SetMicEnable(int enable);
     int AnWBT_GetScanMode();
```

## 复盘与要点
- AIDL 接口"只在尾部追加"是保序安全做法；本提交插在中间，说明两端 .aidl 是成对同步替换的，前提是 APP 与底层服务同版本升级——拆机混装旧服务时将发生 transaction 错位，需在联调记录中注明版本配对关系。
- 接口新增与调用实现分提交是合理的最小变更，但 aidl 提交应注明依赖的服务端版本或配套提交号，占位单号（SIR-XXX）则削弱了可追溯性。

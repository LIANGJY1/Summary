# 无单号(SIR-***) · 账号中心接入读车机 VIN 号、IMEI 号
- **提交**：`c03352e7` | 2026-09-18 | liqingqing | AccountCenter | **功能接入类提交（非缺陷修复，提交头误用 [fixbug] 标签）**
- **缺陷库**：未关联缺陷单号（ids 为占位符 SIR-***，defs 为空）

## 问题
账号中心需要读取车机 VIN 码与通讯模组 IMEI 号用于账号/车辆绑定，此前无此能力。属需求接入，无用户可见缺陷现象。

## 变更概要
改动文件（11 个，+333/-120）：
- 新增 `utils/VehicleInfoManager.kt`（110 行）：单例封装两类读取——
  - VIN：`CarServiceManager.init` + 生命周期回调 `onLifecycleChanged(ready)`，就绪后 `getPropertyAsync(CarPropertyIds.VEHICLE_VIN_CODE, 0)` 异步读取并缓存；未就绪时把回调挂入 `pendingVinCallbacks` 等待通知，含 `release()` 全量清理。
  - IMEI：`TelephonyManager.getImei()` 在 IO 线程读取，校验"纯数字且非全 0"才返回，逐类捕获 `SecurityException/UnsupportedOperationException/IllegalStateException`。
- `AndroidManifest.xml` 新增 `READ_PRIVILEGED_PHONE_STATE` 权限（系统级权限，tools:ignore）。
- 接入点改造：`MyApplication.kt` 初始化；`LoginActivity`/`LoginDialogActivity`/`LoginViewModel`/`CenterActivity`/`BluetoothKeyLoginManager`（二进制不可读，git 显示文本替换实为 ktlint/代码重排）在登录、蓝牙钥匙登录流程中携带 VIN/IMEI；`BootService.kt` 大幅重排（156 行）接入开机链路；`Commons.kt` 删除 4 行旧的占位实现。

## 说明
- 该提交虽带 `[fixbug]` 前缀且影响等级标 B，但 what/why/how 三段均为需求描述，无缺陷根因信息，实为功能接入，不做 bugfix 复盘剖析。
- 值得借鉴的工程点：异步属性读取的"回调挂起-批量通知"模式（`pendingVinCallbacks`）、IMEI 合法性校验（防全 0/非数字脏数据）、权限异常逐类兜底。
- 隐患：`READ_PRIVILEGED_PHONE_STATE` 为 signature|privileged 权限，仅系统预装可用；`BluetoothKeyLoginManager.kt` 在 stat 中显示为二进制（Bin 12078 -> 13318 bytes），git 无法呈现其文本 diff，具体接入内容未能核实。

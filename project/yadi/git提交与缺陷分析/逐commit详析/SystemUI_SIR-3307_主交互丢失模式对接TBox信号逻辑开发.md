# SIR-3307 · 主交互丢失模式对接 TBox 信号逻辑开发

- **提交**：`eb18baae` | 2026-08-26 | ljl | SystemUI | 功能开发（元数据标 bugfix，diff 实为新增对接逻辑，以 diff 为准）
- **缺陷库**：未关联缺陷条目（无 defs 数据）

## 问题
丢失模式（车辆找回功能）此前在 `DigitalKeyVehicleService` 中只有 `TODO` 占位：计划通过 MCU CarProperty 监听丢失模式信号，但协议/信号 ID 一直未定，功能处于未接通状态。

## 根因分析
本单并非修 bug，而是**补齐丢失模式的信号通道**：废弃"等 CarPropertyId"的方案，改为对接 TBox 的 HAL 服务 `vendor.hardware.tbox.ITboxService`。数字钥匙模块在 TboxService 中 clientId=6，丢失模式协议为 `digitalkey_004`：下行 JSON `{"command":{"id":"lost_mode","sub":"set"},"content":{"userid":...,"value":0}}`，`value=0` 开启丢失模式、`1` 关闭；处理完需回 ack（`sub=ack` + `code`）。原 `DigitalKeyConstants`/`DigitalKeyVehicleService` 里的 TODO 与注释式监听代码被移除，改为注释指向新的 TBox 消息路径。

## 关键代码修改
改动文件：`application/SystemUI/src/main/java/com/android/systemui/digitalkey/LostModeTboxClientManager.kt`（新增 265 行）、`DigitalKeyConstants.kt`、`DigitalKeyVehicleService.kt`、`init/InitService.java`、`build.gradle`

```diff
--- a/application/SystemUI/build.gradle
+    implementation files('../../component/commonlibs/vendor.hardware.tbox-V1-java.jar')

--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/LostModeTboxClientManager.kt（新增，节选）
+class LostModeTboxClientManager : BaseManager() {
+    private const val SERVICE_NAME = "vendor.hardware.tbox.ITboxService/default"
+    private const val CLIENT_ID_DIGITAL_KEY = 6
+    private const val RECONNECT_DELAY_MS = 3000L
+    private val deathRecipient = IBinder.DeathRecipient {
+        synchronized(serviceLock) { clearServiceLocked(false) }
+        scheduleReconnect()
+    }
+    private val callback = object : ITboxCallback.Stub() {
+        override fun onMessage(message: TboxMessage?) {
+            workerHandler?.post { handleIncomingMessage(message.content) }
+        }
+    }
```

（`InitService.java` 将 `LostModeTboxClientManager.class` 注册进初始化管理器；`DigitalKeyConstants` 的丢失模式注释从"MCU CarProperty 信号 ID 待补充"改为"TboxService 下行消息，value 0=开/1=关"。）

## 为什么能"修复"
功能从不可用变为可用：`LostModeTboxClientManager` 通过 `ServiceManager` 获取 TBox HAL binder，注册 `ITboxCallback` 接收下行消息，在独立 `HandlerThread` 中解析 JSON，最终调用 `DigitalKeyManager.handleLostModeSignal` 驱动丢失模式 UI/状态；工程上做了较完整的健壮性设计——`IBinder.DeathRecipient` 监听服务死亡并 3 秒重连、`registered` 标志防重复注册、worker 线程隔离 binder 回调、退出时 `quitSafely` 清理。注：TBox 依赖以 jar 包（`vendor.hardware.tbox-V1-java.jar`）形式引入，属二进制依赖更新。

## 复盘与经验
- 对接 HAL/跨进程服务时，DeathRecipient + 延迟重连 + 注册幂等是存活性的"三件套"，新增 `LostModeTboxClientManager` 的骨架可作为其他 TBox 消息（该通道还有其他 clientId）的模板。
- 之前留 TODO 等"协议确认"的方案（CarProperty 监听）被整体替换为 TBox 消息通道，说明跨域信号方案变更后要及时清理旧占位注释，否则两套说明并存会误导后来者。
- binder 回调运行在 binder 线程池，消息处理 post 到自有 HandlerThread 串行化，既避免阻塞 binder 线程又保证消息顺序——跨进程消息处理的标准姿势。

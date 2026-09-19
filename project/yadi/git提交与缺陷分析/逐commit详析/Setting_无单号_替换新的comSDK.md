# 无单号 · 替换新的 comSDK

- **提交**：`ac818f80` | 2026-06-30 | sgh | Setting | feature
- **关联单**：无

**类型**：SDK/AAR 升级提交（aar 二进制替换 + 配套代码删减）。

## 内容一句话
`NsrCommSdk.aar`（L2A 通信 SDK，47477→47611 字节）升级新版本；因新 SDK 移除了屏幕状态上报接口，`SettingVehicleService.kt` 同步删除 `ScreenStateCallbackStub` 内部类及其 register/unregister 共 15 行。

```diff
# application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
-    private var screenStateCallback: ScreenStateCallbackStub? = null
...
-                    IviCommManager.getInstance()?.registerScreenStateCallback(screenStateCallback)
...
-    inner class ScreenStateCallbackStub : IScreenStateCallback.Stub() {
-        override fun onScreenInfo(screenInfo: ScreenInfo) { ... }
-    }
```
```diff
# component/commonlibs/NsrCommSdk.aar | Bin 47477 -> 47611 bytes
```

**要点**：aar 升级提交的关键价值在配套代码——新 SDK 删了 `IScreenStateCallback`，若只换 aar 不删 Stub 会直接编译失败。SDK 升级提交应遵循"二进制 + API 变更适配 + 影响说明"三件套，本提交缺影响说明（ScreenState 上报由谁承接未交代，测试范围仅"替换新的comSDK"）。

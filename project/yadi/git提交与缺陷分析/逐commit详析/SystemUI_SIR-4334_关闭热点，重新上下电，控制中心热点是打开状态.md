# SIR-4334 · 关闭热点重新上下电后控制中心热点显示为打开

- **提交**：`7d73ba3c` | 2026-07-28 | dufan | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
用户已关闭热点，整车重新上下电后，控制中心的热点开关却显示为打开状态。

## 根因分析
`SystemSettingsControllerService` 的 `hotspotState` 属性 getter 负责向控制中心上报热点开关状态，但其实现读取的却是 `wifiManager?.isWifiEnabled`——即 **Wi-Fi 开关状态被当成了热点状态**。上下电后控制中心重新拉取状态时，只要车机 Wi-Fi（STA）是开着的，`hotspotState` 就返回 true，热点图标被点亮，与真实热点状态（`hotspotManager` 的状态）完全脱节；不重新上下电时因为控制中心有本地状态缓存/即时回调，问题不易暴露。缺陷库根因"读取状态 api 错误"正是此意。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt

```diff
--- application/SystemUI/src/main/java/com/android/systemui/cmdcontroller/systemsetting/SystemSettingsControllerService.kt
     private val hotspotState: Boolean
         /**************************************HOTSPOT START */
         get() {  //NOSONAR
-            // 检查WiFi是否开启
-            val isWifiApEnabled: Boolean = wifiManager?.isWifiEnabled ?: false//NOSONAR
+            // 检查热点是否开启
+            val isWifiApEnabled: Boolean = hotspotManager?.isHotspotEnabled ?: false//NOSONAR
             return isWifiApEnabled
         }
```

## 为什么能修复
`hotspotManager`（`HotspotManager.getInstance(mAppContext)`，同文件第 240/249 行定义）才是热点状态的正确来源，改用 `isHotspotEnabled` 后上报值与真实热点状态一致，上下电重新同步时不再被 Wi-Fi 开关状态污染。改动仅两行，无其他调用受影响。隐患在于该错误最初能编译通过并"看起来工作"，说明两个 Manager 的 API 形状相似，容易复制粘贴时串用。

## 复盘与经验
- **状态查询要"问对对象"**：Wi-Fi STA 与 AP（热点）是两个子系统，`isWifiEnabled ≠ isHotspotEnabled`，复制相似代码后必须逐项核对数据源。
- **带缓存/本地状态的 UI 会掩盖读数错误**：只有重新初始化（上下电、重进界面）触发全量状态同步时才暴露，测试需覆盖"重启/重同步"路径。
- **变量命名掩盖语义**：局部变量名叫 `isWifiApEnabled`，实际读的是 STA 开关——名字与取值来源不一致本身就是坏味道，应让代码自证。

# SIR-6034 · 黑夜模式下专注模式仪表区仍显示白天模式

- **提交**：`0572e36f` | 2026-08-25 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 仪表信息

## 问题
黑夜模式下进入专注模式，仪表区（仪表屏同步区域）仍显示白天模式主题，与车机端黑夜模式不一致。

## 根因分析
缺陷库根因"车控没有给仪表发信号"：Setting 应用在系统昼夜模式变化时（`MyApplication.onConfigurationChanged` 中读取 `currentNightMode` 判定 isNight 并切换本应用 UI 资源），只处理了车机端自己的主题切换，**没有把昼夜状态作为 L2A 信号同步给仪表**。仪表侧拿不到 `Theme_Style` 状态，专注模式仪表区便停留在默认的白天样式。修复新增常量 `Constants.THEME_STYLE = "Theme_Style"`（白天黑夜），并在昼夜变化回调中（延迟 62ms）`settingVehicleService.sendL2A(Constants.THEME_STYLE, isNight ? 1 : 0)` 把主题模式发给仪表。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/Constants.java、application/Setting/src/main/java/com/yadea/setting/MyApplication.kt（+4/-1）
```diff
--- application/Setting/src/main/java/com/yadea/setting/Constants.java
+    public static final String THEME_STYLE = "Theme_Style";//白天黑夜
--- application/Setting/src/main/java/com/yadea/setting/MyApplication.kt
@@ onConfigurationChanged 昼夜判定回调内
                 "onConfigurationChanged: currentNightMode -> " + (if (isNight) "night  " else "day  ")
             )
+            val value = if (isNight) 1 else 0
+            settingVehicleService.sendL2A(Constants.THEME_STYLE, value)
         }, 62)
```

## 为什么能修复
昼夜模式每次变化都会把 1（黑夜）/0（白天）写入 `Theme_Style` 信号，仪表专注模式区据此切换对应主题，显示与车机端一致。放在 `onConfigurationChanged` 保证手动切换与自动跟随（时间/光线）两条路径都覆盖。隐患：信号由 Setting 应用发起，Setting 未运行（进程被杀）期间发生的昼夜切换不会同步，依赖该应用常驻或系统级发送；`sendL2A` 延迟 62ms 的魔数是既有节奏，硬等 UI 稳定后发送，若 L2A 通道慢启动仍可能丢首发信号（对比 `58ef7197` 的补偿查询思路，此处无重查兜底）。

## 复盘与经验
- 多屏主题一致性是"状态分发"问题：车机切换主题时必须把状态广播给所有相关屏（仪表/HUD），只改本地 UI 必然出现"主机黑夜里、仪表大白天"。
- 昼夜这类全局状态宜由系统服务/CarService 统一上报，各应用各自发信号容易漏发（本例）或重复发。
- 变更回调里同步外部系统时，考虑首发失败的兜底（重发/查询回读），特别是依赖 L2A 这类慢启动通道的场景。

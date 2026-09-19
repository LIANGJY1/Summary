# 无单号 · 新增Yadea_Trace

- **提交**：`88ebe5e9` | 2026-07-09 | sgh | Setting | feature
- **关联单**：无

## 需求/目标
为设置应用全启动链路（Application → InitService → SettingVehicleService → MainActivity）打上 `Yadea_Trace` 统一日志埋点，输出各阶段 start/success/close 时刻，用于整机开机后设置应用初始化时序的量化排查。

## 实现结构
改动 5 个文件、纯新增 17 行：
- `Constants.java`：新增公共常量 `Yadea_Trace = "Yadea_Trace"`，作为全模块统一 TAG。
- `MyApplication.kt`：`Application_start` / `Application_success` 两个阶段点。
- `init/InitService.kt`：`InitService_start` / `InitService_success`。
- `init/SettingVehicleService.kt`：start / success / close 三点（服务生命周期最长，多打一个关闭点）。
- `ui/activity/MainActivity.kt`：`MainActivity_start`，并新增 `onDestroy` 覆写输出 `MainActivity_close`。

数据流：无业务变化；日志平台按 TAG=`Yadea_Trace` 过滤即可得到设置应用启动-完成-销毁的完整时间轴（比 `9e1506f8` 账号中心的裸打点更进一步，阶段语义化了）。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/MyApplication.kt
+        LogUtils.i(Constants.Yadea_Trace, "Setting Application_start")
+        LogUtils.i(Constants.Yadea_Trace, "Setting Application_success")
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
+    override fun onDestroy() {
+        super.onDestroy()
+        LogUtils.i(Constants.Yadea_Trace, "Setting MainActivity_close")
+    }
```
实现讲解：埋点采用 `<应用名> <组件>_<阶段>` 的扁平命名，start/success 成对出现即可直接算出各阶段耗时；TAG 收口到 `Constants` 常量，避免各处硬编码字符串漂移。

## 复盘与要点
- "start/success 成对 + 常量 TAG"是可复用的最小化 tracing 手法，无需引入任何 trace 框架即可覆盖开机时序问题的大多数场景。
- 与 `9e1506f8`（裸字符串）对比可见同一团队在两天内快速迭代了打点规范：常量化 + 阶段化，后续模块应直接对齐此版式。
- 遗留点：埋点散布各文件靠人肉维护，若某阶段中途抛异常则只见 start 不见 success，日志平台需支持"孤立 start"告警；`InitService` 若被系统重启多次，时间轴会重叠，需结合 pid 过滤。

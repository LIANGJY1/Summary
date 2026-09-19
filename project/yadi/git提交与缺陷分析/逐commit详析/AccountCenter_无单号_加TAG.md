# 无单号 · 加TAG

- **提交**：`9e1506f8` | 2026-07-09 | hedeyuan | AccountCenter | feature
- **关联单**：无

## 需求/目标
为账号中心应用补充启动链路日志：以统一 TAG `Yadea_Trace` 打点 Application 启动与 LoginActivity 的 onCreate，配合团队新推的 `Yadea_Trace` 全链路日志排查体系（同期 `88ebe5e9`、`998e9edb`、`d6736f9b` 等提交同属此工程）。

## 实现结构
改动 2 个文件、纯新增 9 行：
- `di/MyApplication.kt`：启动处加 `LogUtils.d("Yadea_Trace", "AccountCenter Application start")`。
- `ui/login/LoginActivity.kt`：补 `onCreate` 生命周期覆写，打点 `Yadea_Trace` 与原 TAG 两条日志。

数据流：无业务逻辑变化，仅日志输出；`Yadea_Trace` 作为统一 TAG 可被日志采集端按 TAG 过滤，串联各应用的启动时序。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt
+    override fun onCreate(savedInstanceState: Bundle?) {
+        super.onCreate(savedInstanceState)
+        LogUtils.d("Yadea_Trace", "AccountCenter Activity start")
+        LogUtils.d(TAG, "LoginActivity onCreate")
+    }
```
实现讲解：约定大于配置——所有模块统一写死 `"Yadea_Trace"` 字符串作 TAG，日志平台按 TAG 聚合即可还原"哪个应用、哪个阶段"的启动轨迹；同时保留模块自身 TAG 一条，兼顾常规排查。

## 复盘与要点
- 统一 trace TAG 是跨应用启动性能/时序排查的轻量方案：零依赖、零风险，只要求团队纪律性（每个新模块记住补点）。
- 不足：TAG 与消息内容均靠手写字符串，容易拼错或漏打（后续 `88ebe5e9` 专门"新增Yadea_Trace"工具封装应即为此收口）。
- 这类提交 review 成本应极低，但值得检查的点只有：是否打在主线程关键路径（本例是，可接受）与 super.onCreate 是否在前（是）。

# 商业 APM 选型

本组按工具或紧密相关 API 归档；题目保留来源答案，标题编号在本组内连续。

<!-- 来源：05-observability/03-apm-platforms.md · Q6 -->

**Q1: [learning] 选型 Sentry、APMPlus、Bugly 这类商业 APM 时，为什么功能清单不足以形成验收结论？以 Sentry 的 UI profiling 为例说明版本分支风险。**

商业平台的能力随版本、合同与部署形态变化，同一产品不同制品线的边界也不同——功能清单是供应商承诺，验收要用项目实际拿到的 artifact 和构造样本做 PoC。通用评审维度至少包括：SDK 覆盖（Android 版本、targetSdk、ABI、主流网络库）、SDK 自身开销（crash、启动、线程、包体）、符号化与样本还原率、远程采样与 kill switch、数据所有权与删除 SLA、16 KB ELF/ZIP 对齐、退出导出成本。

Sentry 的 UI profiling 说明了版本分支的必要性：SDK `8.51.0` 起按系统版本走两条路径——Android 15（API 35）及以上调用系统 `ProfilingManager`，结果是 Perfetto trace，但系统对请求限流、不保证每次都有 profile、且该后端不支持 app-start profiling。API 34 及以下回退到 legacy ART runtime tracer，存在 Sentry 文档列出的 runtime crash 风险。若 API 34 及以下新增 crash 集中在 `libart.so`、`art::Trace::StopTracing` 附近，应先关闭 legacy profiling 或降低采样率再分组复现，不能全部归因给业务 native 代码。Session Replay 默认遮盖文本与图片也不等于满足合规——PixelCopy 策略与 View hierarchy 时序不一致时可能错位，涉及支付、身份的页面要逐屏检查录制结果。

Bugly 的教训是制品线要拆开：普通版 Android changelog 停在 `3.4.4`（2021 年），不能推断 Pro `4.4.x` 的能力。Pro 的 Maven 最新是 `4.4.7.16` 而公开 changelog 只到 `4.4.7.8`，这之间的版本不能按号猜行为。crash、ANR、OOM 默认 100% 上报且不支持采样，其他监控项才支持采样，直接影响事件费用与流量估算。16 KB 适配必须使用 `com.tencent.bugly_16kb` 的 groupId，只升版本号不换 groupId 不算选择了 16 KB 制品。APMPlus 则要核对国内与海外制品的上报地域差异，PoC 设备需加入白名单强制进入采集，否则“没有数据”可能只是没命中采样。以上均为第三方商业 SDK，未本地核对。

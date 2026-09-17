# Market and Source Ledger

## 使用规则

动态结论必须记录采集日期、样本范围、来源 URL 和局限性。招聘网站页面可能失效，面经只能作为线索，不能替代官方文档或源码。薪资只作为谈判参考，不写成承诺。

## 研究维度

每轮刷新至少统计：岗位名称、城市、经验要求、Android 版本、AOSP/Framework、Java/Kotlin、C/C++、HAL/VHAL、CarService、音频、多屏、性能、安全、CAN/SOME-IP/QNX、算法/机考、薪资、公司类型。

建议按 OEM、Tier 1、方案商三组采样；按初级、高级、资深三档分层；每次至少记录岗位链接、发布日期/抓取日期和关键词命中。输出“频率 + 代表性岗位 + 结论 + 不确定性”，不凭印象排序。

## 当前技术主线

- AAOS 多屏和仪表集成需要理解 Presentation、虚拟显示、Display/WindowManager 和安全关键显示边界。
- 多音区需要理解 Car Audio Service、音频焦点、Audio Control HAL、输出 bus、动态路由和 occupant zone。
- AAOS 14-16 的重点跟踪方向包括 AIDL/HAL 演进、多用户多显示、动态音频、Scalable UI、可配置音频策略和安全显示。
- 新岗位优先验证“能否读 AOSP、改系统服务/HAL、定位性能和安全问题”，而不是只看 Android App 技术栈。

## 2026-09 初步招聘结论

公开样本只有 6 条，不能代表全市场，但足以校准当前计划：

- **核心门槛**：Framework/Native/HAL、Binder/JNI、系统服务、性能稳定性和问题定位反复出现；
- **车机差异项**：CarService/VHAL、CarAudio、多屏、座舱中间件、QNX/CAN/ASPICE 常作为加分或岗位分化项；
- **语言组合**：Java/Kotlin 负责 Android 层，C/C++ 更常出现在平台、HAL 和性能岗位；
- **岗位跨度**：同一市场同时存在 15-30K 的普通 Framework 岗和 25-60K、14-16 薪的高级/专家岗位，不能只用一个薪资锚点判断岗位级别；
- **计划影响**：P0 保留 Framework 主链、C++/JNI、性能排查、AAOS 车机；P2 保留 QNX/CAN/ASPICE 概念，不扩展为主线。

样本是公开搜索结果和职位聚合页，存在重复、过期和页面截断；真实投递时仍以职位详情和面试反馈更新。

## 官方来源

- [AOSP Automotive displays](https://source.android.com/docs/automotive/displays)：多屏、仪表、虚拟显示和安全关键显示边界。
- [AOSP Automotive audio overview](https://source.android.com/docs/automotive/audio)：AAOS 音频职责、bus 输出和音频策略。
- [AOSP multi-zone audio routing](https://source.android.com/docs/automotive/audio/audio-multizone-routing)：多音区、OccupantZone、焦点和动态路由。
- [AOSP Audio control HAL](https://source.android.com/docs/automotive/audio/audio-control-hal)：Audio Control HAL 与车载音频控制。
- [AOSP Scalable UI](https://source.android.com/docs/automotive/scalableui)：现代多面板车机 UI、窗口系统和版本方向。
- [Android for Cars releases](https://developer.android.com/training/cars/platforms/releases)：Android/AAOS 版本兼容和行为变化。
- [AAOS 25Q2 release notes](https://source.android.com/docs/automotive/start/releases/aaos-25q2)：VHAL、Audio Control API 等演进线索。
- [Configurable audio policy engine](https://source.android.com/docs/automotive/audio/configurable-audio-policy-engine)：AAOS 14/16 可配置音频策略演进。

## 本地源码来源

- 源码目录与读写边界（`AAOS13_study` 可改，三个真实项目只读）的唯一落点：[work-project-analysis/README.md](./work-project-analysis/README.md)。
- Summary 已有 Android Framework、AAOS、音频、显示、性能、Binder、ART、SELinux 和电源笔记。
- 可迁移的语言、设计和 Android 机制知识统一进入本目录的父目录，本文件只维护岗位研究和外部来源。
- 只把能服务面试题、实践或项目证据的源码继续下钻；所有版本判断以实际源码和官方 release notes 交叉验证。

## 招聘研究记录

| 采集日期 | 平台/公司 | 岗位/城市/级别 | 关键词摘要 | 薪资 | 链接 | 可信度/局限 |
|---|---|---|---|---|---|---|
| 2026-09-17 | BOSS/聚合页 | 座舱 Android Framework，上海，5-10年 | Framework、CarService、VHAL、Java/C++ | 15-30K | [岗位页](https://www.zhipin.com/zhaopin/47d5859141f18b701X1809S0/) | 聚合结果，需二次确认 |
| 2026-09-17 | BOSS/聚合页 | Framework/车载系统，武汉/成都/北京等 | AMS/PMS/Input/Display/Graphics、CarAudio、性能 | 18-50K·14薪 | [岗位页](https://www.zhipin.com/zhaopin/2f7cf5a05e19cb531XZ92NW7/) | 多岗位聚合，非单一 JD |
| 2026-09-17 | 猎聘 | 座舱软件开发/测试，深圳，4-8年 | BSP、OS、中间件、HAL/Framework、C/C++、QNX、ASPICE | 30-60K·15薪 | [岗位页](https://www.liepin.com/a/78450077.shtml) | 猎头发布，要求较宽 |
| 2026-09-17 | 猎聘 | Android Framework，南京，5-10年 | HAL/native/Java、AMS/WMS/PMS/Input、性能定位 | 15-25K·14薪 | [岗位页](https://www.liepin.com/job/1945654683.shtml) | 页面较旧，作为技术样本 |
| 2026-09-17 | 猎聘 | 智舱中间件音频，北京，3年以上 | AudioManager/AudioPolicy/AudioFlinger/Audio HAL、QNX | 未注明 | [岗位页](https://www.liepin.com/a/74080113.shtml) | 音频专项，不代表通用岗 |
| 2026-09-17 | Lever | Android Automotive Software Developer | AOSP、system services、Stable AIDL HAL、C++17、性能/稳定性 | $90K-$110K | [岗位页](https://jobs.lever.co/syntronic/2c23013e-ae24-41d5-98b1-e0cdb71aefcf) | 海外岗位，不能直接套用国内薪资 |

## 结论变更日志

| 日期 | 原结论 | 新证据 | 结论变化 | 对计划的影响 |
|---|---|---|---|---|
| | | | | |

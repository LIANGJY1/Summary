# bundletool 设备交付体积测量

本组解释 bundletool 的设备交付大小分析。

<!-- 来源：06-app-optimization/02-app-size.md · Q10 -->

**Q1: [learning] AAB 上传后设备实际收到什么？bundletool get-size total 两条命令为什么不能相减？**

AAB 是发布格式不是安装包，设备不会安装 `.aab`。Google Play 按 AAB 生成并签名 base APK、按 ABI/语言/密度拆分的 configuration APK、feature APK，以及可能的 install-time 资源 split。本地用 `bundletool build-apks` + `get-size total --device-spec` 估算某设备的压缩传输大小：不带 `--modules` 统计首次下载时安装的所有模块。带 `--modules` 指定集合时会把所选模块的依赖一并计入。两者口径不同（首装全集 vs 指定集合含依赖），不能相减推导单个 feature 自身大小。未提供设备规格时结果可能是设备维度的最小-最大值，不能当单机基线。

三种尺寸要分开记录：AAB 上传大小（检查发布制品）、设备交付大小（由 SDK 版本、ABI、语言、密度、模块集合与交付时机决定）、安装后占用（受原生库提取、dexopt、资产展开与应用数据影响）。`build-apks` 本地签名可用 debug key，仅适合测试。渠道发布仍要用 Play 测试轨道或渠道环境验证。

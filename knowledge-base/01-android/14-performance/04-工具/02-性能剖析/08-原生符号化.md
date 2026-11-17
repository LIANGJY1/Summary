# Native Symbolization

本组收录该工具或 API 的采集、使用与结果解释题。

<!-- 来源：04-reliability/02-stability-and-crash.md · Q18 -->

**Q1: [learning] 栈回溯（unwind）与符号化（symbolication）为什么是两个独立环节？错误符号为什么也会“看起来对”？**

unwind 用崩溃现场（寄存器、栈内存、映射）按回溯规则恢复调用帧，symbolication 用匹配构建的符号文件把地址还原为函数与行号。两者依赖的证据不同，任何一环版本不匹配都会产出可读但错误的堆栈。AAOS13 的 `crash_dump` 使用 `libunwindstack`，它能读取 DWARF CFI、32 位 ARM 的 `.ARM.exidx`、frame pointer，并识别 ART 的 JIT 与 Dex 虚拟帧。

回溯信息来源的取舍：DWARF CFI（`.eh_frame`/`.debug_frame`）在优化构建下也能描述寄存器恢复规则，但元数据可能被裁剪。frame pointer 读取简单、适合高频采样，但任何一层省略 FP、尾调用或栈破坏都会截断（arm64 默认保留 FP、arm32 默认不保留）。启发式栈扫描只能提供候选地址，误报高。错误符号“看起来对”的原因是：错误 ELF 也可能在相同偏移附近存在函数并输出语法完整的函数名，所以服务端校验不能只看“是否输出函数名”，而要核对 Build ID 完全匹配、地址落在可执行 `PT_LOAD` 范围、函数范围覆盖输入地址、同一事件同模块帧使用同一 Build ID，不匹配时明确标记缺失或错误，不能回退到同名最新版符号。

<!-- 来源：04-reliability/02-stability-and-crash.md · Q19 -->

**Q2: [learning] Native 符号产物应该怎样归档与验证？AGP 的 debugSymbolLevel 两档有什么区别？**

符号库以 ELF Build ID 为主键，因为版本号、ABI 和 `.so` 文件名不能唯一定位二进制。每次发布至少归档：

1. 最终 APK/AAB、版本、渠道与 ABI 清单。
2. 每个发布 `.so` 的 Build ID。
3. 保留符号的 ELF 或拆分出的 debug 文件。
4. 编译器与裁剪配置。
5. 第三方 Native SDK 版本及其符号获取方式。

归档后验证 stripped 与 unstripped 文件的 Build ID 完全一致，并用该构建制造已知崩溃确认符号化命中。

AGP 4.1+ 可为 AAB 生成 native debug symbols：`SYMBOL_TABLE` 只能恢复函数名，`FULL` 还提供文件和行号（官方文档口径）。产物随 AAB 交给 Play Console 并按团队保留周期归档。第三方依赖若在进入构建前已被剥离调试信息，AGP 无法重新生成——构建日志出现相关告警时应阻止发布或留下明确的风险豁免记录。使用 Breakpad 时，`dump_syms` 输出的 `MODULE` 行 identifier 直接作为符号目录键（`symbols/<模块名>/<identifier>/<模块名>.sym`），不要自行猜测或截断，模块同名但构建不同必须落在不同标识目录。

<!-- 来源：05-observability/01-observability-governance.md · Q5 -->

**Q3: [learning] 发布构建的符号化产物为什么要与二进制绑定归档？缺 mapping 时门禁应该怎么做？**

混淆名与 Native 地址只在同一次构建内有意义：同一 `versionCode` 可能被重新构建，单靠它选不出正确的 R8 mapping。Native 侧要靠 ABI、`.so` 文件名、ELF Build ID 与 PC 相对地址匹配未剥离产物或符号包，重新链接会改变地址布局与 Build ID——即使源码提交与版本名相同，也不能拿另一份 ELF 还原线上地址。所以每个可发布构建把 APK/AAB 摘要、mapping 内容摘要、原生符号文件、Git 提交与构建变体归档为同一组不可变产物，CI 在发布时复制到只增不改的仓库。

门禁规则：启用 R8 却缺 mapping、包含 Native 库却缺对应 Build ID 的符号文件时直接阻断发布，否则上线后只能把栈标记为“无法反混淆”，Native 崩溃无法还原函数。问题签名要等符号化之后再生成（异常类型 + 还原后的稳定帧序列 + 规范化消息 + 构建范围。Native 用致命信号 + Build ID + 符号化函数 + 模块内偏移区间 + ABI），混淆名和绝对地址不能做跨版本问题组主键。服务端保留原始事件与问题组的映射，聚类算法更新后可重新归组而不改原始事件。

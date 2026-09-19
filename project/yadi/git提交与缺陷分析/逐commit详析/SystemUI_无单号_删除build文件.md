# SIR-XXXX · 删除build文件（SystemUI 构建产物清理）

- **提交**：`81c3082b` | 2026-06-29 | liujinfeng | SystemUI | 特殊类型：build 产物清理提交（标题即"[SIR-XXXX]删除build文件"，占位单号）
- **缺陷库**：未关联单号（SIR-XXXX 为占位，无真实缺陷记录）

## 问题
SystemUI 模块曾把 `build/` 输出整目录误提交入库：本次一次性删除 **3223 个文件、110,216 行**，全部为构建产物，无任何源码改动。

## 根因分析
以 diff 实际内容为准（`--name-status` 过滤后无一条非 build 路径）：被删除的均为生成物与中间产物——kapt/DataBinding 生成类（`*Binding.java`、`DataBinderMapperImpl`）、AIDL 生成桩（`IWeatherService.java`、`ICustomGestureService.java` 等）、`.class` 文件、`package-aware-r.txt`（4934 行）、`manifest-merger-release-report.txt`、`previous-compilation-data.bin` 等。这与 4 天前 `094cdccd`（accountcenter 同类清理）同源；且两天前 `b0a39fec` 已把根 `.gitignore` 从 `application/*/build/` 扩为 `**/build/`——本提交正是 SystemUI 这类更深层级模块的产物被新规则覆盖后做的存量清理。

## 关键代码修改
改动文件：SystemUI 模块 `build/` 目录下全部 3223 个产物文件（删除），代表性条目：
```diff
- .../build/generated/aidl_source_output_dir/.../IWeatherService.java        | 270 -
- .../build/generated/source/kapt/.../systemui/databinding/ActivityMainBinding.java | 68 -
- .../build/intermediates/.../NetworkRequestErrorDialogFragment.class        | Bin 4403 -> 0
- .../build/intermediates/.../package-aware-r.txt                            | 4934 -
- .../build/intermediates/.../previous-compilation-data.bin                  | Bin 248748 -> 0
```

## 为什么能修复
不修复运行期问题，消除的是仓库层面的三重伤害：110k 行噪音 diff 淹没真实提交；AIDL/DataBinding 生成类入库后，与本地重新生成的版本不一致会产生"幽灵冲突"与诡异编译错误；`previous-compilation-data.bin`、`.class` 等二进制无法 delta 压缩，永久膨胀仓库。清理后首次构建会重新生成全部产物，无功能风险。

## 复盘与经验
- **`.gitignore` 扩规则要跟一次存量清理**：`**/build/` 只挡新增，不删已跟踪文件；需配 `git rm -r --cached` 类清理（本提交即存量清理动作）。
- **生成代码入库是"幽灵冲突"制造机**：kapt/AIDL 产物随构建环境变化，入库后每次合码都可能假冲突，务必只留源。
- **占位单号（SIR-XXXX）也在污染历史**：清理类提交也应挂真实任务单或明确标注 chore，便于统计时排除。

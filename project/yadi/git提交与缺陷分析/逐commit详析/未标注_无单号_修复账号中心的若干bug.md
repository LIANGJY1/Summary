# 无单号 · 修复账号中心的若干bug（实为构建产物清理）

- **提交**：`094cdccd` | 2026-06-25 | hedeyuan | (未标注) | 特殊类型：build 产物清理提交
- **缺陷库**：未关联单号

## 问题
提交标题为"修复账号中心的若干bug"，但 diff 实际内容与标题不符：**全部 1034 个变更文件均为删除 `build/`、`intermediates/` 下的构建产物**，没有任何源码（Java/Kotlin/XML 资源）改动。

## 根因分析
以 diff 实际内容为准：这是把账号中心模块（com.yadea.accountcenter）误提交进仓库的构建输出目录整体删除的清理提交。被删除内容包括：
- kapt/DataBinding 生成代码（如 `ActivityCenterBinding.java`、`ActivityLoginBindingImpl.java`、`DataBinderMapperImpl.java`、`BR.java`）
- 中间产物（`annotationProcessors.json`、`redirect.txt`、`app-metadata.properties`）
- 二进制资源（MiSans 全套字体 .ttf、`baseline.prof`、`classes.dex`、`graph.bin`、`-br.bin` 等）

因文件太多且全部为产物，未能从 diff 判断所谓的"账号中心 bug 修复"具体内容——本提交不含逻辑修复。

## 关键代码修改
改动文件：accountcenter 模块的 `build/` 与 `build/intermediates/` 全目录删除（1034 个文件，含约 20 个 .ttf/.dex/.bin 二进制文件）

```diff
（无源码 hunk，示例条目）
- .../build/generated/source/kapt/release/com/yadea/accountcenter/DataBinderMapperImpl.java | 128 -
- .../build/intermediates/assets/release/fonts/MiSans-Regular.ttf            | Bin 8122324 -> 0
- .../build/intermediates/dex/release/mergeDexRelease/classes.dex           | Bin 8586916 -> 0
```

## 为什么能修复
不能"修复"任何运行期问题；它消除的是**版本库污染**：约 8MB 的 dex、8MB 级字体、生成 Binding 类不再进入 git 历史，避免后续每次拉取/合并的无谓体积与假冲突。隐患是若本地确实依赖了误入仓的产物路径，清理后首次构建会重新生成，无实质风险。

## 复盘与经验
- **提交前检查 `.gitignore`**：`build/`、`intermediates/`、kapt 生成目录必须在忽略清单里，否则"修 bug"提交会夹带上千个产物文件，淹没真实 diff。
- **提交信息与 diff 对不上是危险信号**：code review 时若标题说"修复若干 bug"而 stat 全是删除产物，应让作者拆分/重写提交说明。
- **二进制产物入库的代价**：字体、dex、prof 文件会永久增大仓库体积（git 无法有效 delta 压缩），清理只能止损、不能缩减历史。

# 无单号 · 添加忽略文件（清理构建产物）

- **提交**：`a1dfb97d` | 2026-06-25 | dufan | 仓库管理 | other
- **关联单**：无

## 需求/目标
为前一提交（32b5926c）的疏漏善后：新增 .gitignore，并从索引中移除已被误提交的 Gradle 本机缓存。

## 实现结构
改动文件：.gitignore（+3 行），同时删除 .gradle/ 下 executionHistory.bin（164MB）、fileHashes.bin、各类 lock 等构建产物，.idea/misc.xml 一并移除。

## 关键代码
```diff
+ *.iml
+ .gradle/
+ .idea/
```
（.gitignore 追加忽略规则，`git rm --cached` 方式移除已入库文件，本地文件保留）

## 复盘与要点
- 这是"初始化提交夹带本机产物"的标准补救，当天完成，响应很快。
- 教训（重复一次因为代价真实）：仓库历史上这 164MB 二进制永久存在，clone 体积从此降不下来。`.gitignore` 必须先于首次 `git add .`。

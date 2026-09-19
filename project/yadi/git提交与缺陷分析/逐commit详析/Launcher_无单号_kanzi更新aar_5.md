# 无单号 · kanzi 更新 aar
- **提交**：`3283ece5` | 2026-09-09 | liqingqing | Launcher | **二进制依赖更新（非代码 bugfix）**
- **缺陷库**：未关联单号（标题中单号打码为 SIR-***）

## 问题
3D 车模（Kanzi 渲染）相关修复以 aar 升级形式交付，无源码 diff。

## 根因分析
（特殊类型提交）`application/Launcher/libs/kanzi-release.aar` 从 299667844 字节升级到 481953723 字节（约 286MB→460MB），为 Kanzi 渲染库的二进制更新。提交信息仅注明"kanzi 更新aar"，测试范围"3D 车模"。结合同批次上下文（83ab8df5 修 SIR-7922 桌面白屏、3283ece5 相邻时间提交），该 aar 大概率包含 Kanzi 侧渲染/连接问题的配套修复，但 aar 内部改动无法从本仓库 diff 考证。

## 关键代码修改
改动文件：application/Launcher/libs/kanzi-release.aar
```diff
 application/Launcher/libs/kanzi-release.aar | Bin 299667844 -> 481953723 bytes
```
（二进制更新，无代码 hunk。）

## 为什么能修复
以二进制整体替换 Kanzi 库，渲染侧修复随之生效；体积增长约 60%，需关注 APK/系统分区体积与启动加载耗时。无法从 diff 验证修复内容，回归依赖 3D 车模功能测试。

## 复盘与经验
- aar/二进制类"修复"必须附版本号与变更说明（changelog 或 issue 链接），否则后续回溯（如本批次复盘）只能凭上下文推测。
- 库体积从 286MB 涨到 460MB 属显著膨胀，依赖升级应同步评估包体与内存占用。
- aar 升级与源码修复（如 83ab8df5 的渲染看门狗）常是同一问题两侧的配套改动，复盘时应成对查看。

# SIR-3248 · Launcher写入座椅名被静默丢弃（写路径也用过期缓存定位用户）·之三

- **提交**：`74777571` | 2026-07-25 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 3D车模（SIR-3248 第三笔，写路径补丁）

## 问题
`4deed122`/`cc65c389` 修复后仍有残留：Launcher（3D 车模）进程写座椅名时，若该用户数据是 Setting 进程创建的，写入会被静默丢弃，车控车设仍收不到新名字。

## 根因分析
`SeatUserManager.setSeatPositionName()` 的写入流程是：读内存 `cache` → `getUserStartPosition(userId)` 在缓存中定位该用户的座椅名区块 → 写入。多进程场景下，Launcher 进程的 `cache` 是自己上次加载的快照：若用户数据由 Setting 进程创建后落盘，Launcher 缓存里**根本没有这条用户记录**，`getUserStartPosition` 返回 -1，方法 `if(start==-1){ return }` 直接退出——一次改名的写入无声失败，磁盘上永远是旧数据。此前的修复只给读路径（`getSeatNamesFromJson`）加了 `cache = loadFromDisk()`，写路径漏掉了，形成"读得到新数据、写不进新数据"的半修复状态。提交 [what/why] 对机制的描述与代码逐字吻合。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
     @Synchronized
     fun setSeatPositionName(position: Int, name: String) {
+        cache = loadFromDisk()
         val userId = SettingsUtils.getGSettingLong(CACHE.USER_ID)
         val start = getUserStartPosition(userId)
         if(start==-1){ return}
```

## 为什么能修复
写入口在定位用户前先 `loadFromDisk()`，Launcher 缓存被刷新为磁盘最新内容，Setting 进程创建的用户记录能够被找到，`getUserStartPosition` 不再误返回 -1，写入照常执行；方法本身 `@Synchronized`，重载发生在临界区内，无并发窗口。隐患：读+写每次都打磁盘，高频改名时 IO 放大（当前场景频次极低可接受）；多进程同时写仍无互斥文件锁，理论上存在后写覆盖先写，本场景双方写入频率低、目标区块不同，风险可控。

## 复盘与经验
- 修缓存一致性时读/写路径要一起过一遍：只修读路径是"能看到新数据"，写路径用旧缓存定位照样丢数据——本单正是三笔提交逐步补全的典型。
- `getUserStartPosition` 返回 -1 就静默 return，让"写失败"不可见；这类早退分支至少要打日志（或抛出），否则问题只能靠终态现象倒查。
- 跨进程共享文件 + 进程内缓存是车机多应用协作的常见结构，约定应是"每次操作前重载"，把缓存当纯加速而非事实源。

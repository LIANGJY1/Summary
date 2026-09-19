# 无单号 · 优化读取 share 目录用户信息（每次读盘刷新缓存）

- **提交**：`3075d8ed` | 2026-07-27 | sgh | Setting | feature
- **关联单**：无（提交说明里写明动机："可能数据不同步问题……每次读取数据从新读取文件"）

## 需求/目标
解决 `SeatUserManager` 内存缓存与 share 目录磁盘文件不同步的问题：所有读写入口在操作前先 `loadFromDisk()` 重新加载，保证多入口/多进程场景下读到最新用户配置。

## 实现结构
单文件（+11/-14）：`common/manager/SeatUserManager.kt`
- 12 个读写方法（`addUser/syncConfigBetweenUsers/removeUser/getSelectedPosition/saveSelectedPosition/setHudConfig/getHudConfig/getUserStartPosition/setSeatSaveStatus/isSeatPositionSaved/userExists` 等）开头统一插入 `cache = loadFromDisk()`；
- 顺带删除已无调用方的 `getUserSlots()`（14 行）。

## 关键代码
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
     @Synchronized
     fun addUser(userId: Long, sourceUserId: Long = USER_CUSTOMER): Int {
+        cache = loadFromDisk()
         LogUtils.d(TAG, "addUser，userId=$userId")
         val users = cache.optJSONObject("users") ?: JSONObject().also { cache.put("users", it) }
```
```diff
     @Synchronized
     fun removeUser(userId: Long) {
+        cache = loadFromDisk()
         val users = cache.optJSONObject("users") ?: return
```
实现讲解：share 目录的 JSON 文件可能被其他进程（如账号服务）直接改写，原来的进程内长驻缓存会读到旧数据——这正是多账户切换时座椅/名字不同步的来源。改为"每次操作前重读磁盘 + @Synchronized + saveToDisk"，用读盘成本换一致性，是典型的 cache-aside 退化用法。

## 复盘与要点
- 正确的取舍场景：配置读频率低（账户切换/点击级别）、一致性要求高，读盘开销可接受；若是高频路径（如每次信号刷新）则应改用文件监听或跨进程通知。
- 遗留风险：`getSeatPositionName/getSeatNamesFromJson` 等 getter 未加 `loadFromDisk()`（本 diff 未出现），读路径覆盖不全时仍可能读到旧缓存；"有些读刷新、有些不刷新"反而更难排查。
- 删除 `getUserSlots` 提示槽位数组逻辑已被 `getSavePosition/isSeatPositionSaved` 的按需计算取代，死代码清理及时。

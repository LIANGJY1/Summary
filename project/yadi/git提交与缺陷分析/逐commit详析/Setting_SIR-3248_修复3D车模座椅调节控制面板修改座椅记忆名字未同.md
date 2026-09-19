# SIR-3248 · 3D车模改的座椅记忆名未同步到车控页（跨进程缓存过期）· Setting 侧

- **提交**：`cc65c389` | 2026-07-23 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 3D车模（本提交为 Setting 侧配套修复）

## 问题
在 3D 车模（Launcher/Kanzi）的座椅调节控制面板修改座椅记忆名字后，进入设置的车控车设页，名字还是旧的。

## 根因分析
座椅名称存储在公共组件 `SeatUserManager`（CommonTools），它对 JSON 文件做了一层内存 `cache`。3D 车模所在进程（Launcher）写入名称后，Setting 进程里的 `SeatUserManager` 单例缓存**不会自动失效**——`getSeatNamesFromJson(userId)` 直接读 `cache`，拿到的是本进程上次加载的旧数据；而 `VehicleControlFragment` 又只在 `initView` 里 `loadSeatPositionNames()` 一次，进入页面不会重查。两个机制叠加：跨进程写 + 本进程缓存不失效 + 页面不重读，车控页永远看不到 3D 车模侧的改名。提交 [how]"进入界面重新获取"即针对后一环。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt；component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
+    override fun onResume() {
+        super.onResume()
+        loadSeatPositionNames()
+    }
--- a/component/CommonTools/src/main/java/com/yadea/common/manager/SeatUserManager.kt
     fun getSeatNamesFromJson(userId: Long): Array<String> {
         LogUtils.d(TAG, "getSeatNamesFromJson userId=$userId")
+        // 重新从磁盘加载缓存，避免多进程写入后读取到旧数据
+        cache = loadFromDisk()
         val defaultNames = arrayOf("位置1", "位置2", "位置3")
         val start = getUserStartPosition(userId)
```

## 为什么能修复
`getSeatNamesFromJson` 每次调用前强制 `cache = loadFromDisk()`，把"跨进程写后本进程缓存过期"这一根因在读路径上消除；`onResume` 里补 `loadSeatPositionNames()`，保证每次进入车控页都触发一次新读，UI 与磁盘上的最新名称一致。副作用：读取变为每次打磁盘，频率低（进页/重命名时）可接受；真正的多进程一致性并未完全解决（写入方仍不通知读取方），若两进程同时读写仍有竞态窗口，后续提交（`4deed122`/`74777571`）继续在写路径上加固。

## 复盘与经验
- "单例 + 内存缓存"一旦被多个进程使用，缓存一致性就不再是实现细节而是需求：跨进程读写点必须重新过磁盘或走跨进程通知。
- 页面级数据若可能被外部（其他进程/其他入口）修改，`onResume` 刷新是最低成本的兜底。
- 修复跨进程数据问题时先补读路径（重读磁盘见效快），再考虑写通知/广播等彻底方案，分层推进。

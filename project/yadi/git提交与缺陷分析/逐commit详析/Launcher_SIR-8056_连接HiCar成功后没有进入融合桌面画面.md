# SIR-8056 · 连接 HiCar 成功后未进入融合桌面（未切换到互联 tab）

- **提交**：`fcc5bfc5` | 2026-09-10 | dufan | Launcher | bugfix（提交头误标为 [feature]，实为缺陷修复）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 待测试验证 · 域 手车互联

## 问题
连接 HiCar 成功后打开应用列表页，界面停在默认"应用列表"tab，没有进入融合桌面/手机互联（CarConnectFragment）画面。

## 根因分析
`AppListActivity.initViewpager()` 读取 `DeviceConnectManager.getCurrentConnectType()` 后按类型分支处理：类型 1 直接 `setCurrentItem(1, false)`，类型 2/3（车机互联类连接）只调用 `updateCarConnectStatus(true, 设备名)` 更新标题，切换 tab 的语句写在分支内但与状态更新割裂；而 `updateCarConnectStatus()` 在 `connected=false` 的 else 分支里还会强制 `viewPager?.currentItem = 0`。于是 HiCar 连接场景下存在两个问题：连接分支没有可靠地把 pager 切到 tab 1；任何一次断连状态回调（或 else 分支命中）都会把 pager 拉回 tab 0，用户看到的始终是应用列表，"连接后未切换 tab"。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt（1 文件 +1/-9）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/function/applist/AppListActivity.kt
@@ initViewpager() 的 when 分支
             2, 3 -> {
                 updateCarConnectStatus(
                     true,
                     SettingsUtils.getGSetting(CONNECT_DEVICE_NAME)
                 )
-                viewPager?.setCurrentItem(1, false)
             }
-
-            else -> {
-                updateCarConnectStatus(
-                    false,
-                    resources.getString(R.string.car_connect)
-                )
-            }
@@ updateCarConnectStatus()
         if (connected && !deviceName.isNullOrEmpty()) {
             ...
             adapter.notifyDataSetChanged()
             }
+            viewPager?.currentItem = 1
         } else {
             ...
             adapter.notifyDataSetChanged()
             }
-            viewPager?.currentItem = 0
         }
```

## 为什么能修复
tab 切换收敛到 `updateCarConnectStatus()` 内部：只要 connected=true 就切到 tab 1（融合桌面），连接初始化与状态回调两条路径统一；同时删除 else 分支的 `currentItem = 0` 强制回跳，未连接时保持用户所在 tab，也删掉了 initViewpager 里对未知连接类型重置连接状态的 else，避免误清状态。改动面小且语义集中；隐患是"断连不再回到 tab 0"属于行为变更，若产品预期断连后回应用列表，需要另行确认。

## 复盘与经验
- 同一 UI 状态（pager 当前页）被多处命令式设置（init 分支、状态回调、extra 触发）是典型"切换丢失/互相覆盖"来源，应收敛到单一状态同步函数。
- `else` 分支兜底重置状态要谨慎：未知枚举值或中间态触发 else 会产生与意图相反的强跳转。
- 提交头 [feature] 误标会导致缺陷追溯断链，JSON 元数据已标记 mistag，复盘统计时应以 diff 实质为准。

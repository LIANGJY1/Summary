# SIR-5788 · 首次点"恢复出厂"误弹"非P档，不可操作"toast

- **提交**：`0c46cba5` | 2026-08-11 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：首次获取无缓存数据 / sol：改为异步获取）

## 问题
车辆明明处于 P 档，首次点击恢复出厂设置弹窗中的"恢复"按钮，却弹出"非P档，不可操作"的 toast，恢复流程被拦截；再次点击才正常。

## 根因分析
`SystemFragment` 恢复确认回调 `confirm()` 里通过 `settingVehicleService.getAnyProperty(CarPropertyIds.PCU_ACTUALGEARFEED)` **同步读取**档位信号再判断 `value != 0`。首次进入该页面时车机属性服务尚无该信号的缓存值（信号未上报过/连接刚建立），同步 get 返回默认值（非 0，如 0 以外的初始值或错误码），被误判为"非 P 档"，直接 toast 拦截并 return——真实档位根本没被读到。本质是"用可能未就绪的同步缓存做安全判断"，首次时序下缓存必然缺失，所以必现。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
             override fun confirm(content: Any?) {
-                val value: Any =
-                    settingVehicleService.getAnyProperty(CarPropertyIds.PCU_ACTUALGEARFEED)
-                log("reset gear: $value")
-                if (value != 0) {
-                    ToastUtils.showMsgToast(requireContext(),
-                        getString(R.string.reset_factory_failed_with_no_gear))
-                    return
-                }
-                // 清除用户座椅和HUD配置数据
-                SeatUserManager.clearAllData()
-                ...sendL2A / sendVehicleProperty / ACTION_FACTORY_RESET 广播...
+                lifecycleScope.launch(Dispatchers.IO){
+                    val value: Any =
+                        settingVehicleService.getAnyProperty(CarPropertyIds.PCU_ACTUALGEARFEED)
+                    log("reset gear: $value")
+                    if (value != 0) {
+                        ToastUtils.showMsgToast(requireContext(),
+                            getString(R.string.reset_factory_failed_with_no_gear))
+                        return@launch
+                    }
+                    // 清除用户座椅和HUD配置数据
+                    SeatUserManager.clearAllData()
+                    settingVehicleService.sendL2A(Constants.FACTORY_RESET, 1)
+                    settingVehicleService.sendVehicleProperty(VehiclePropertyIds.FACTORY_RESET, 4)
+                    val intent = Intent(Intent.ACTION_FACTORY_RESET)
+                    ...
+                    requireActivity().sendBroadcast(intent)
+                }
             }
```

## 为什么能修复
把档位读取与后续恢复动作整体移入 `Dispatchers.IO` 协程异步执行：绕车机服务真实取值（提交 `[how]改为异步获取`），首次也能拿到真实档位，不再拿默认值误判；读到的确实非 P 档时仍正确 toast 拦截（`return@launch`），安全检查语义保留。同时把恢复出厂的系列操作（清数据、发信号、发广播）放到 IO 线程，避免阻塞主线程。隐患：异步化后 toast 依赖 `requireContext()`，若用户在协程执行瞬间关闭页面会因 fragment 已分离抛异常；安全拦截从"点击即拦"变为"异步后拦"，极短窗口内用户看不到即时反馈。

## 复盘与经验
- 车辆信号同步 get 有"缓存未就绪"语义，首次访问必然踩坑；安全类判断要么订阅信号缓存最新值，要么显式异步拉取，不能裸信同步返回值。
- `value != 0` 这类哨兵比较应先确认"取不到值"与"真实非 P 档"两种情况如何区分（错误码/默认值与业务值混判是误拦根源）。
- 恢复出厂这类低频高危操作，主线程做信号 IO 顺带引发 ANR 风险，异步化是一石二鸟，但注意生命周期兜底（Toast 用 applicationContext 更稳）。

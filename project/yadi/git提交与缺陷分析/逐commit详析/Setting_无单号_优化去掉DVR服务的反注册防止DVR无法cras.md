# 无单号 · 去掉 DVR 服务反注册，防止 DVR 异常
- **提交**：`b3955ddc` | 2026-09-04 | sgh | Setting | bugfix（防御性优化）
- **缺陷库**：未关联单号（提交标题为占位符 SIR-XXX）

## 问题
行车记录仪（DVR）相关页面销毁时反注册回调（`setDvrCallBackListener(null)`），提交说明称该操作会导致"DVR 无法 crash"（语句不通，结合上下文应理解为：反注册调用异常影响 DVR 服务/进程稳定，或设置进程在此处出错）。

## 根因分析
`SafetyMonitorFragment.onDestroyView()` 里通过 `runCatching { dvrManager.setDvrCallBackListener(null) }` 反注册 DVR 回调。当 DVR 服务端已退出/未连接时，该跨进程调用可能触发服务端空指针或绑定异常（runCatching 只能兜住客户端侧异常，兜不住服务端 crash），标题即"防止 DVR 无法 crash"。由于无缺陷单关联，详细现象以提交说明为准。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt`

```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SafetyMonitorFragment.kt
         log("lifecycle onDestroyView savedScrollY=$savedScrollY")
         super.onDestroyView()
         dismissFormatLoadingDialog()
-        runCatching { dvrManager.setDvrCallBackListener(null) }
-            .onFailure { log("dashcam release dvr callback fail: ${it.message}") }
     }
```

## 为什么能修复
直接删除 `onDestroyView` 中的反注册调用，Fragment 销毁时不再向 DVR 服务发起这次跨进程调用，服务端不再因该调用异常崩溃；代价是回调监听可能残留到下次页面重建（由 `dvrManager` 生命周期或重注册覆盖），属于以"少做一次危险调用"换稳定的取舍。无单号关联，缺少缺陷现场的验证闭环，风险在于问题是否真正消除依赖实测。

## 复盘与经验
- 跨进程服务的反注册调用并不总是安全的：服务端可能已死、Binder 可能异常，"规范地置空回调"反而成为崩溃源；对不稳定外设服务，评估后可以选择不反注册、依赖管理类统一生命周期。
- 提交标题"防止DVR无法crash"语句不通、单号占位（SIR-XXX），此类无单提交应补写清楚现象与证据，否则复盘时只能靠猜。
- `runCatching` 只保护调用方进程，兜不住服务端崩溃——跨进程调用的异常边界要两侧都考虑。

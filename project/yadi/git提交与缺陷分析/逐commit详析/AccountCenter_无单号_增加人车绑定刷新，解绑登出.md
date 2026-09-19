# 无单号 · [SRS_ACCOUNT_006] 增加人车绑定刷新，解绑登出
- **提交**：`7790ea71` | 2026-09-08 | liqingqing | AccountCenter | feature
- **关联单**：无（SRS_ACCOUNT_006）

## 需求/目标
车辆端登录态需要与手机端的人车绑定关系保持一致：当用户在手机 App 上解绑车辆后，车机端应能感知并自动清除本地登录态（退登），避免"手机已解绑、车机仍登录"的悬空状态。

## 实现结构
- `service/BootService.kt`（+67）：新增 `checkUnbindAndLogout()`，在原有 WiFi 联网刷新 token 的两个回调点（onAvailable / onCapabilitiesChanged）追加调用；查询人车绑定列表 `getBindList(VIN)`，若当前登录 userId 不在列表中则清空本地登录态。
- `ui/center/CenterActivity.kt`（+23/-9）：进入账号中心拉取绑定列表后同样校验，不在列表则退登并跳转 LoginActivity；同时把 `clearAll()` 的私有清态实现替换为共用的 `AccountLogoutUtils.clearLoginState()`。
- `EnergyManagement/.../dialog_reservation_charging.xml`：弹窗宽度 660dp→840dp（顺带的 UI 微调，与本需求无关）。

数据流：WiFi 网络事件（或打开账号中心）→ 查询绑定列表接口 → 比对 userId → 不在列表则 `AccountLogoutUtils.clearLoginState()` 清空 Settings.Global 中的 USER_ID/电话/token 及账号缓存。

## 关键代码
```kotlin
// application/AccountCenter/src/main/java/com/yadea/accountcenter/service/BootService.kt
private fun checkUnbindAndLogout() {
    // 未登录（USER_ID 未写入或已清空）直接跳过
    val currentUserId = SettingsUtils.getGSettingLong(Constants.CACHE.USER_ID)
    val accessToken = SettingsUtils.getGSetting(Constants.CACHE.ACCESS_TOKEN)
    if (currentUserId <= 0L || accessToken.isNullOrEmpty()) {
        return
    }

    // 避免短时间内重复查询（与 token 刷新共用同一间隔）
    val now = System.currentTimeMillis()
    if (now - lastUnbindCheckTime < MIN_REFRESH_INTERVAL) {
        return
    }
    lastUnbindCheckTime = now

    val repository = RequestRepository.instance ?: RequestRepository()
    repository.getBindList(Commons.VIN).subscribe(object :
        Observer<BaseResponse<List<BindUserResponse>?>> {
        ...
        val stillBound = bindUsers.any { it.userId == currentUserId }
        if (!stillBound) {
            LogUtils.w(TAG, "userId=$currentUserId no longer bound to vin=${Commons.VIN}, clear local login state")
            AccountLogoutUtils.clearLoginState()
        }
    })
}
```

```kotlin
// application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt
val bindUsers = response.data
// 接口成功但当前登录用户已不在人车绑定列表 -> 判定已被手机端解绑 -> 本地退登
// 注意：仅在"接口成功 + 不在列表"时退登，接口失败/网络异常不处理，避免断网时误踢
if (bindUsers != null && bindUsers.none { it.userId == userId }) {
    LogUtils.w(TAG, "userId=$userId no longer in bind list, force logout")
    clearAll()
    startActivity(Intent(this@CenterActivity, LoginActivity::class.java))
    finish()
    return
}
```

实现讲解：复用 BootService 原有的"WiFi 可用即刷新"通道搭车解绑校验，用 `lastUnbindCheckTime` 做 60 秒节流（MIN_REFRESH_INTERVAL 与 token 刷新共用），避免网络抖动导致接口被高频调用。退登条件设计得非常保守——只有"接口明确成功且 userId 确实不在列表"才踢登录，接口失败/网络异常一律跳过，防止断网误踢。

## 复盘与要点
- **保守退登原则可复用**：远程状态驱动的本地登出，必须以"接口成功 + 明确不在列表"为唯一触发条件，失败路径绝不退登，这是车机弱网环境下防误踢的标准做法。
- **搭车式触发**：解绑校验挂在已有的 token 刷新网络回调上，不新建监听器，改动小且天然与刷新节流对齐；代价是校验频率与 token 刷新耦合，无法单独调整。
- **遗留风险**：本提交引用了新类 `AccountLogoutUtils`，但该文件并未包含在本提交中（git 确认 `exists on disk, but not in '7790ea71'`），单独编译本提交会失败——由 17 分钟后的下一个提交 `f51c4008` 补交"漏传文件"，属于典型的提交遗漏事故。

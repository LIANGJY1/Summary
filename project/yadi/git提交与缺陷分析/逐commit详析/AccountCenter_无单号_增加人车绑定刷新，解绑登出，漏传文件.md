# 无单号 · [SRS_ACCOUNT_006] 增加人车绑定刷新，解绑登出，漏传文件
- **提交**：`f51c4008` | 2026-09-08 | liqingqing | AccountCenter | feature（实为补交遗漏文件的修复性提交）
- **关联单**：无（SRS_ACCOUNT_006）

## 需求/目标
补交上一提交 `7790ea71` 中被引用但未加入版本库的新类 `AccountLogoutUtils.kt`，修复编译缺失。

## 实现结构
- `utils/AccountLogoutUtils.kt`（新增，23 行）：单例 object，提供 `clearLoginState()`，清空 Settings.Global 中的 USER_ID / USER_TELEPHONE / ACCESS_TOKEN / REFRESH_TOKEN，并调用 `AccountProfileUtils.clear()`、`AccountRoleUtils.clear()` 清账号缓存与角色缓存。

该文件是 `7790ea71` 中 BootService（后台解绑校验退登）与 CenterActivity（页面退登）共同引用的退登工具类，本提交使其落到版本库中，两处退登行为收敛为一份逻辑。

## 关键代码
```kotlin
// application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/AccountLogoutUtils.kt
object AccountLogoutUtils {

    fun clearLoginState() {
        // 清除登录状态
        SettingsUtils.setGSetting(Constants.CACHE.USER_ID, 0L)
        SettingsUtils.setGSetting(Constants.CACHE.USER_TELEPHONE, "")
        SettingsUtils.setGSetting(Constants.CACHE.ACCESS_TOKEN, "")
        SettingsUtils.setGSetting(Constants.CACHE.REFRESH_TOKEN, "")
        AccountProfileUtils.clear()
        AccountRoleUtils.clear()
    }
}
```

实现讲解：把原先 CenterActivity 私有的 `clearAll()` 五行清态代码提升为全局 object 工具，让后台 BootService 的自动退登与前台页面退登共用同一份实现，消除"两处清态字段不一致"的隐患。类注释明确写出了两个调用方和设计动机。

## 复盘与要点
- **漏传文件的典型现场**：前一提交引用了未提交的新文件，IDE 本地编译通过但换机器/CI 必挂；17 分钟后补交说明是提交前未做 `git status` 全量检查。提交前用 `git status` + 编译校验可完全避免。
- **同 Change-Id 追加提交**：本提交复用了 `I026830db...` 这个 Change-Id，配合新 Change-Id 双写，说明团队用 Gerrit 习惯把补交挂在同一需求评审链下，便于评审追溯。
- **收敛重复逻辑**：借补交机会把清态逻辑收敛为单一出口（object 工具），后续新增登录字段时只需改一处，是"先重复、后收敛"的轻量重构样本。

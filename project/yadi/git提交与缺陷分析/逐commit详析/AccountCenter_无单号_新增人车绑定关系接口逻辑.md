# 无单号 · 账号中心新增人车绑定关系接口逻辑（含失效用户配置清理雏形）

- **提交**：`794e0267` | 2026-07-29 | hedeyuan | AccountCenter | feature
- **关联单**：无

## 需求/目标
账号中心接入 TSP"人车绑定关系列表"接口：进入个人中心时按 VIN 拉取绑定列表，为"识别已被删除的失效用户并清理其本地配置文件"打基础（本提交为接口打通 + 清理逻辑雏形）。

## 实现结构
- `Commons.kt`：新增接口地址常量 `API_GET_BIND_LIST`（暂为占位 `xxxxxxxxxx`）
- `ApiService.kt`：Retrofit 新增 `@POST getBindList(@Body vin)`
- `RequestRepository.kt`：新增 `getBindList()`，标准 `subscribeOn(io).observeOn(main)` 封装
- `CenterViewModel.kt`：新增 `bindListLiveData` 与 `getBindList()`（Observer 四回调 + postValue）
- `CenterActivity.kt`：`onCreate` 尾部发起请求；`initObserver` 订阅返回并调 `delInvalidUserConfig()`——清 `ShareConfigUtils.clearConfig(userId)` 配置文件 + 写 `INVALID_USER_ID` 标记
- `component/CommonTools/Constants.kt`：CACHE 区新增 `INVALID_USER_ID` 键

数据流：CenterActivity → ViewModel.getBindList(VIN) → Retrofit → LiveData → Activity 观察者 → 删除本地用户配置。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt
@@ -98,6 +102,17 @@
+    /**
+     * 从TSP的"人车绑定关系"接口返回的列表中，判断哪个用户被删了，删除已失效用户的配置文件
+     * @param invalidUserId 无效用户的id
+     */
+    private fun delInvalidUserConfig(invalidUserId: Long) {
+        LogUtils.d(TAG, "delInvalidUserConfig: delete invalid user config")
+        //删除data/share/目录下的无效用户的偏好设置配置文件，如：data/share/user_4323455642310027023_vehicle_config.properties
+        ShareConfigUtils.clearConfig(invalidUserId ?: 0L)
+        SettingsUtils.setGSetting(Constants.CACHE.INVALID_USER_ID, invalidUserId ?: 0L)
+    }
```
实现讲解：按模块既有 MVVM 套路（Api 常量 → Retrofit → Repository → ViewModel LiveData → Activity）横向复制一个接口通道。清理逻辑以注释保留 for 循环骨架，当前用硬编码假 ID `242425525252525L` 直调 `delInvalidUserConfig`，是接口未就绪时的调试桩。

## 复盘与要点
- 接口 URL 占位（`xxxxxxxxxx`）+ 硬编码测试用户 ID 说明这是"接口先行"提交，服务端未定稿就先打通端到端链路，联调风险靠后续提交收敛（两天后 `2aa850ca` 即同一需求的正式版）。
- `delInvalidUserConfig` 除删文件外还写全局 `INVALID_USER_ID` 标记，供其他模块（如座椅配置）感知失效用户，属跨模块约定的雏形。
- 遗留风险：`getBindList` 响应体复用 `LoginStateResponse` 语义不符；onError postValue(null) 时 Activity 侧未判空。

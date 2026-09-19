# [SRS_UserCenter_012] · 账号中心人车绑定接口定稿 + 车主标识

- **提交**：`2aa850ca` | 2026-08-07 | liqingqing | AccountCenter | feature
- **关联单**：SRS_UserCenter_012

## 需求/目标
将 `794e0267` 的占位接口正式落地（真实 URL/GET+Query/专用响应模型），并新增"车主标识"能力：按 VIN 拉取绑定用户列表，匹配当前登录用户的 `accountRole`，个人中心显示/隐藏车主角标。

## 实现结构
- `data/model/BindUserResponse.kt`（新增）：`userId/accountRole/bikeNickName` 三字段模型，替换误用的 `LoginStateResponse`
- `utils/AccountRoleUtils.kt`（新增）：角色常量 UNKNOWN=0/OWNER=1/NON_OWNER=2；`save(currentUserId, bindUsers)` 匹配当前用户取角色并写全局设置（非法值归 UNKNOWN）；`clear()` 复位
- `Commons.kt`：`API_GET_BIND_LIST` 从占位 `xxxxxxxxxx` 定为 `yadea/vehiclebase/user-bike/listBindUserByVin`
- `ApiService.kt`：POST+Body 改为 **GET+@Query(vin)**，响应类型 `List<BindUserResponse>`
- `CenterActivity.kt`：删除 794e0267 的失效用户清理调试桩（`delInvalidUserConfig` + 硬编码假 ID），改为 `handleBindListResponse()` 判断 `isSuccess` 后 `AccountRoleUtils.save` + 控制 `ivCarOwnerFlag` 显隐；退出登录/请求失败均 `clear()` 并隐藏角标
- `LoginActivity/LoginDialogActivity/LoginViewModel`：登录链路同样请求绑定列表，记 `loggedInUserId`，登出/失败清角色
- `activity_center.xml`：新增车主角标视图

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/utils/AccountRoleUtils.kt
@@ -0,0 +1,26 @@
+object AccountRoleUtils {
+    const val SETTINGS_KEY = "accountRole"
+    const val UNKNOWN = 0
+    const val OWNER = 1
+    const val NON_OWNER = 2
+
+    fun save(currentUserId: Long?, bindUsers: List<BindUserResponse>): Int {
+        val accountRole = bindUsers
+            .firstOrNull { it.userId == currentUserId }
+            ?.accountRole
+            ?.takeIf { it == OWNER || it == NON_OWNER }
+            ?: UNKNOWN
+
+        SettingsUtils.setGSetting(SETTINGS_KEY, accountRole)
+        return accountRole
+    }
+
+    fun clear() {
+        SettingsUtils.setGSetting(SETTINGS_KEY, UNKNOWN)
+    }
+}
```
实现讲解：角色判定收敛到单一工具类："先按 userId 匹配、再白名单校验角色值、缺省归 UNKNOWN"，UI 侧只消费返回值切角标显隐。登录、个人中心、登出、请求失败四条路径全部成对调用 save/clear，角色状态与登录态强绑定。

## 复盘与要点
- "接口先行占位 → 服务端定稿 → 正式落地"的两段式提交（794e0267→2aa850ca 间隔 9 天）在联调期很实用，但正式落地时要把调试桩（假 ID 清理逻辑）一并摘除，本次处理干净。
- `AccountRoleUtils` 把"魔法数字角色值 + 全局设置持久化 + 非法值兜底"封装为 3 个明确 API，是跨页面共享小状态的可复用范式。
- 遗留风险：`bindUsers.firstOrNull{userId==current}` 匹配不到时（后端未同步绑定关系）角标静默隐藏，与"请求失败"不可区分，排查需看日志。

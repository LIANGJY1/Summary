# 无单号 · 手车互联（Setting）添加连接后的互斥逻辑

- **提交**：`d08aa955` | 2026-07-29 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
Setting 模块的 `DeviceConnectManager` 与 Launcher 同步添加 CarPlay/HiCar/CarLink 连接互斥：已有任一通道连接时，其他通道的新连接请求直接拒绝（不上位），仅空闲时放行。

## 实现结构
单文件 `application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt`（+9/-1），只改 `setDeviceConnectStatus()` 开头的门卫判断。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/init/DeviceConnectManager.kt
@@ -20
             CARLINK -> 3
             else -> return
         }
-        if (mCurrentConnectType != type && !isConnected) return
+        if (mCurrentConnectType != type) {
+            if (isConnected) {
+                if (mCurrentConnectType != 0) {
+                    return
+                }
+            } else {
+                return
+            }
+        }
 
         when {
             isConnected -> mCurrentConnectType = type
```
实现讲解：原判断"类型不同且是断开事件才 return"只处理了单连接场景；新逻辑展开为三层：同类型放行；异类型连接时仅当当前空闲（==0）放行；异类型断开一律忽略。与 Launcher `9012fffa` 同一分钟段提交，构成同一需求的两侧改动——Launcher 负责主动断开旧通道，Setting 负责拒绝新连接请求。

## 复盘与要点
- 同一互斥规则要在 Launcher/Setting 两份 `DeviceConnectManager` 里各改一遍，逻辑漂移风险高；互斥状态本可下沉到公共组件（如 Carlib/状态单例）统一维护。
- 嵌套 if 展开后语义清晰，但 `mCurrentConnectType != 0` 这类魔法数字（0/1/2/3）三处硬编码，建议抽枚举/常量。
- 被拒连接未发任何用户提示事件，用户感知是"连不上但没原因"，后续可补 toast/弹窗。

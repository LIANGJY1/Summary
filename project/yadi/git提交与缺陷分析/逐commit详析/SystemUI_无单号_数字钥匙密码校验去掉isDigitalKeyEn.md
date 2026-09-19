# 无单号 · 数字钥匙密码校验去掉 isDigitalKeyEnabled 判断，以 MCU 为准

- **提交**：`c0ad2c83` | 2026-07-22 | ljl | SystemUI | feature
- **关联单**：无（`4c68fa99` 的次日修正）

## 需求/目标
修正前一天 MCU 密码校验链路的准入条件：密码锁屏页面本身由 `MCU_WAKE_UP_PASSWORD_UNLOCK_PAGE` 信号触发显示，页面出现即代表 MCU 侧已启用数字钥匙密码，故不再需要 `isDigitalKeyEnabled()` 前置判断；同时更新 `android.car.jar`（二进制）。

## 实现结构
2 个文件：`KeyguardActor.kt` 的 `verifyPin` 中删去 `digitalKeyManager.isDigitalKeyEnabled() &&` 条件，直接调 `requestPasswordCheck(mCurrentPin)`；注释同步改写为"密码界面由 MCU 唤醒信号触发显示，密码以 MCU 侧为准"。`component/frameworkLibs/libs/android.car.jar` 二进制更新（1113127 → 1113439 字节），配合新信号 ID。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/keyguard/actor/KeyguardActor.kt
-        // 数字钥匙场景：优先请求MCU校验密码（MPU_TO_MCU_PASSWORD_CHECK），
+        // 密码界面由MCU_WAKE_UP_PASSWORD_UNLOCK_PAGE信号触发显示，密码以MCU侧为准，
+        // 优先请求MCU校验密码（MPU_TO_MCU_PASSWORD_CHECK），
         // 发送失败（车信号服务未就绪等）时回退到本地比对
         val checkRequested = try {
-            val digitalKeyManager = DigitalKeyManager.getInstance(mContext)
-            digitalKeyManager.isDigitalKeyEnabled() && digitalKeyManager.requestPasswordCheck(mCurrentPin)
+            DigitalKeyManager.getInstance(mContext).requestPasswordCheck(mCurrentPin)
         } catch (e: Exception) {
```
实现讲解：`isDigitalKeyEnabled()` 大概率读取某个本地开关/系统属性，若它与 MCU 实际状态不同步，会出现"页面弹了但校验仍走本地比对"的不一致。删除 MPU 侧判断、以"页面来源信号"作为唯一事实来源，是一条更干净的信任链。

## 复盘与要点
- 状态准入判断重复是联调期常见 bug：页面显示条件与校验条件各自判断 `isDigitalKeyEnabled`，两边数据源不同步即互相矛盾；应统一由"谁唤醒了页面"决定行为。
- 次日即修正说明 MCU 对接在快速联调中；`android.car.jar` 随代码同提交更新，是车机框架接口演进的常规操作，需注意 jar 与系统镜像版本强绑定。

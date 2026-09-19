# SRS_VehSetting_031 · pp0需求：迎宾模式、迎宾座椅、迎宾灯光接入信号

- **提交**：`2ff72549` | 2026-09-02 | sgh | Setting | feature
- **关联单**：SRS_VehSetting_031（标题引用，无系统单号）

## 需求/目标
迎宾三件套（模式/座椅/灯光）按最新 CAN 规范从 MPU_TO_MCU 内部信号切换为 CCU 下发信号；迎宾音效信号（5504）整体下线；并修正迎宾模式开关绑错控件的问题。

## 实现结构
- `CarPropertyIds.kt`：5501~5503 三个常量重命名 `MPU_TO_MCU_WELCOME*` → `CCU_WELCOMEMODESET/CCU_WELSEATSWITCH/CCU_WELLIGHTSWITCH`（值不变）；`MPU_TO_MCU_WELCOMESOUNDSWITCH(5504)` 删除。
- `CarPropertyMapping.kt`：三信号 sendId 换名，recId 保持 MCU_TO_MPU 反馈不变；迎宾音效条目删除。
- `SettingVehicleService.kt`：注册表与 handlerMap 同步——音效的属性注册与 `welcomeSoundEffectSwitch` 的属性驱动被移除（该 LiveData 转为无源状态，页面改用缓存值）。
- `SceneModeFragment.kt`：`getAnyProperty` 读取、三个 `checkAndDisable*IfNeed` 联动判断换新 ID；修复迎宾模式开关监听误挂在 `swWelcomeSeat` 上的绑定错误（改挂 `swWelcomeMode` 并写 `welcomeModePendingState`）。

数据流：迎宾模式开关 → `CCU_WELCOMEMODESET` 下发（1=开/2=关）+ L2A → MCU 反馈 → LiveData → 回显与"模式开时其余全关则禁用"联动。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
@@ -311,13 +311,12 @@
             // 迎宾模式开关
-            swWelcomeSeat.switchCompat.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
-                welcomeSeatPendingState = isChecked
+            swWelcomeMode.switchCompat.setClickFastWithRebound(viewLifecycleOwner.lifecycleScope) { isChecked ->
+                welcomeModePendingState = isChecked
                 val value = if (isChecked) 1 else 2
                 settingVehicleService.sendVehicleProperty(
-                    CarPropertyIds.MPU_TO_MCU_WELCOMEMODEGEAR,
+                    CarPropertyIds.CCU_WELCOMEMODESET,
                     value,)
```
```diff
--- a/component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
@@ -1772,22 +1772,18 @@
-    const val MPU_TO_MCU_WELCOMEMODEGEAR: Int = 5501
+    const val  CCU_WELCOMEMODESET: Int = 5501
...
-    /**
-     * 迎宾音效
-     */
-    const val MPU_TO_MCU_WELCOMESOUNDSWITCH: Int = 5504
```

实现讲解：与 `71008d83`（氛围灯）同型的协议换轨提交，额外完成两件事：删除迎宾音效信号（功能裁剪，页面从"开关+档位"演变为联动禁用逻辑）以及修复 fc29efff 迎宾模式改开关时留下的绑定笔误——监听挂在了座椅开关上，导致点座椅开关实际下发的是迎宾模式值。

## 复盘与要点
- "常量重命名 + 全仓引用替换"是 CAN 换轨的标准动作；但删除信号时必须清理注册表、handler、mapping、UI 引用四层，本提交 UI 层的 `welcomeSoundEffectSwitch` 残留观察是潜在坏味道。
- 绑错控件的 bug（swWelcomeSeat vs swWelcomeMode）源于两个控件属性相邻 + 复制粘贴，用 ViewBinding 时同名前缀控件尤其要核对 id。
- pp0 阶段频繁换轨说明 CAN 规范未冻结；把"发/收 ID 翻译"集中在 Carlib mapping 层的价值在换轨期被反复验证。

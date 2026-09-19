# 无单号 · 场景迎宾三开关"至少留一"互斥置灰逻辑重构

- **提交**：`9bdf8eb8` | 2026-09-10 | sgh | Setting | bugfix（互斥置灰逻辑优化，未关联单号）
- **缺陷库**：未关联单号

## 问题
迎宾模式开启时，迎宾座椅/灯光/音效三个开关需满足"至少保留一个开启"的互斥约束（最后一个开启的开关应置灰禁止关闭），旧实现的置灰时机与判定都有缺陷，且代码重复混乱。

## 根因分析
`SceneModeFragment` 旧实现为三个近似复制的函数 `checkAndDisableSeatIfNeed`/`checkAndDisableLightIfNeed`/`checkAndDisableSoundIfNeed`，存在四类问题：① 只在对应开关 `state==true` 的 UI 更新里触发，迎宾模式档位变化或其他开关变化时不刷新，置灰状态会滞留；② 判定数据源混用 `settingVehicleService.getAnyProperty(CCU_WELCOMEMODESET/CCU_WELLIGHTSWITCH)`（直读原始属性，可能拿到未刷新值）与 LiveData，口径不一；③ 局部变量命名与语义相反（`val lightOn = (getAnyProperty(...) ?: 0) == 0` 实际是"灯为关"），`shouldDisable = gear != 0 && lightOn && soundOn` 极易误读；④ 置灰用自管 alpha（ALPHA_DISABLED=0.3f）+ overlay 的 `updateSwitchDisabledState`，与全局 `setGrayState` 扩展并存两套机制。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt（1 文件 +32/-79）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SceneModeFragment.kt
@@ 四个信号 observe 回调各追加一行
             updateWelcomeModeGearUI(state==1)
+            refreshWelcomeSwitchGrayState()
@@ 删除三个 checkAndDisableXxxIfNeed + updateSwitchDisabledState + ALPHA 常量，新增：
+    private fun refreshWelcomeSwitchGrayState() {
+        val modeOn = settingVehicleService.welcomeModeGear.value == 1
+        val seatOn = settingVehicleService.welcomeSeatSwitch.value == 1
+        val lightOn = settingVehicleService.welcomeLightSwitch.value == 1
+        val soundOn = settingVehicleService.welcomeSoundEffectSwitch.value == 1
+
+        val seatLocked = modeOn && seatOn && !lightOn && !soundOn
+        val lightLocked = modeOn && lightOn && !seatOn && !soundOn
+        val soundLocked = modeOn && soundOn && !seatOn && !lightOn
+
+        mBinding.swWelcomeSeat.setGrayState(!seatLocked)
+        mBinding.swWelcomeLight.setGrayState(!lightLocked)
+        mBinding.swWelcomeSound.setGrayState(!soundLocked)
+    }
```
（顺带修正三处 logClick 打印 `isChecked` 为 `value`，删除未用的 `welcomeModeGearState` 字段）

## 为什么能修复
新实现把"互斥置灰"收敛为单一纯函数：输入是四路 LiveData 的当前值，输出三个开关的锁定态；四个观察回调（迎宾模式档位、座椅、灯光、音效）全部触发重算，任何一路变化都会重新评估，消除旧版"只在自身开启时算一次"的时机漏洞；`locked = 模式开 && 本开 && 其余全关` 的对称表达直接对应"至少保留一个"的需求语义，命名不再反向。代码净减 47 行。副作用：`welcomeModeGear` LiveData 与 `CCU_WELCOMEMODESET` 原始读数的口径差异需确认一致；置灰改用 setGrayState 后视觉表现（无 0.3 透明度）与旧版略有差别。

## 复盘与经验
- "至少保留一个"类互斥约束不要为每个控件写一份判定函数，写一个全量重算的 refresh 函数、让所有相关状态源触发它，天然避免时机漏洞。
- 布尔变量命名（xxxOn 与 ==0/==1）是车机信号代码的高危区，本例旧代码 lightOn 实为"灯关"，重构时先纠正语义再改逻辑。
- 置灰机制应在模块/平台层统一（setGrayState 扩展），自管 alpha+overlay 的局部方案会造成视觉与行为不一致。

# VIR-156 · HUD 高度调节不记忆

- **提交**：`f70bd267` | 2026-07-07 | sgh | Setting | bugfix
- **缺陷库**：未关联单号（提交带 VIR-156 单号，缺陷库 defs 为空）

## 问题
HUD（抬头显示）高度调节后不记忆，重进设置页/重启后高度回到旧值或滑块位置显示异常。

## 根因分析
HUD 高度滑条使用自定义控件 `GearSwitchViewNew`，外部（设置持久化/信号回调）通过 `setCurrentGear()` 回放记忆值。方法内有两处状态 bug：
1. **动画期间档位被"恢复"成旧值**：`startPosition` 计算块里临时把 `currentGear` 设为 `oldGear` 取位置，事后却写 `currentGear = tempCurrent`（`tempCurrent` 就是 `oldGear`），把外层刚赋的新档位覆盖回去——后续基于 `currentGear` 的保存/记忆拿到的永远是旧档位，这正是"不记忆"的直接机制。
2. **同值早退跳过刷新**：入口 `if (gear !in minGear..maxGear || gear == currentGear) return`，当记忆值与当前值相同（尤其动画进行中 UI 还停在半路）时直接返回，滑块位置不会被纠正到目标位置，且 `isSnapping` 残留 true。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt`（+16/-15）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt  setCurrentGear()
-        if (gear !in minGear..maxGear || gear == currentGear) return
+        if (gear !in minGear..maxGear) return
+        // 即使档位相同，也需要确保 UI 正确刷新（处理外部信号回调场景）
+        val needUpdate = gear != currentGear
         val oldGear = currentGear
         currentGear = gear
         val startPosition = if (isSnapping) {
             val tempCurrent = oldGear
             currentGear = oldGear
             val pos = getCurrentPosition()
-            currentGear = tempCurrent          // ← 恢复成了 oldGear，覆盖掉新档位
+            currentGear = gear  // 恢复为新档位
             pos
         } ...
-        if (animate) {
+        if (animate && needUpdate) { ... } else {
             dragCurrentPosition = endPosition
+            isSnapping = false
         }
```
其余为 `abs`/`AnimatorListenerAdapter` 改为全限定名的 import 清理，无逻辑影响。

## 为什么能修复
`currentGear` 在快照计算后不再被旧值覆盖，之后读取 `currentGear` 做持久化/回显的路径拿到的都是新档位，"记忆"链路恢复；同值场景不再早退，UI 位置强制对齐 `endPosition` 并清除 `isSnapping`，消除"滑块停在半路且状态卡死"的残留。副作用很小：同值刷新多一次 invalidate。

## 复盘与经验
- **"临时改状态再恢复"的写法极易恢复错**：`currentGear = tempCurrent` 看似对称，实则把 oldGear 又写回去。临时状态改动建议用局部变量计算（把 gear 作为参数传给 `getCurrentPosition()`），不要全局字段来回赋值。
- **写入路径正确 ≠ 记忆生效**：记忆链路是"UI→保存→回放→UI"，任何一环读过期的 `currentGear` 都表现为"不记忆"，排查时要沿数据流逐点断点。
- **早退守卫会吞掉"幂等刷新"需求**：`相同值 return` 对用户拖动合理，对外部信号回放却是错误的——回调类入口应保证 UI 与状态最终一致。

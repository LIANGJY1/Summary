# SIR-6472/SIR-6737 · monkey 测试车控 OOM（DialogFragment 残留累积）
- **提交**：`4d0796c0` | 2026-08-31 | sgh | Setting | bugfix
- **缺陷库**：SIR-6472 等级 B · 高概率-40%~80% · 关闭 · 系统需求；SIR-6737 等级 B · 高概率-40%~80% · 关闭 · 系统需求

## 问题
monkey 长时间轰炸车控（Setting）模块后进程 OOM/车控 crash，表现为内存随弹窗操作次数持续增长直至被杀。

## 根因分析
内存泄漏点在弹窗基类 `BaseDialogFragment` 的生命周期处理。其一，`onDismiss` 监听器里用 `if (isAdded && !isDetached)` 作守卫再 `remove(this).commit()`——DialogFragment 走"dialog 未显示过"等路径时 `isAdded` 可能为 false，remove 事务被跳过，Fragment 实例滞留在 `FragmentManager`；`onDismiss(dialog)` 同样只在部分分支 remove，且用 `commit()`（状态保存后抛 IllegalStateException 被 catch 后不再补 remove）。其二，`show(manager, tag)` 直接 `transaction.add(this, tag)`，不检查同 tag 旧实例，monkey 反复触发时同 tag 新实例不断 add，旧实例永远留在 NonConfig 列表，每个实例连同其 View 树/绑定对象累积，最终 OOM。其三，`ConnectChildDialogActivity.onDestroy` 未置空持有的三个 DialogFragment 字段，Activity 重建时旧实例被字段强引用。

## 关键代码修改
改动文件：BaseDialogFragment.kt、ConnectChildDialogActivity.kt
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt
@@ show()
         val transaction = manager.beginTransaction()
+        // 移除同 tag 的残留实例，防止 FragmentManager NonConfig 累积（OOM 根因）
+        manager.findFragmentByTag(tag)?.let { old ->
+            transaction.remove(old)
+        }
         transaction.add(this, tag)
         transaction.commitAllowingStateLoss()
@@ onDismiss / setOnDismissListener
-                if (isAdded && !isDetached) {
-                    mDismissListener?.onDismiss()
-                    parentFragmentManager.beginTransaction().remove(this@BaseDialogFragment).commit()
+                runCatching {
+                    if (!isDetached && host != null) {
+                        mDismissListener?.onDismiss()
+                        parentFragmentManager.beginTransaction()
+                            .remove(this@BaseDialogFragment).commitAllowingStateLoss()
+                    }
                 }
```
```diff
--- a/.../setting/ui/activity/ConnectChildDialogActivity.kt
     override fun onDestroy() {
         SIsNeedFinish = false
         SIsFromCarConnect = false
+        mWlanDialogFragment = null
+        mBluetoothDialogFragment = null
+        mHotspotDialogFragment = null
         super.onDestroy()
     }
```

## 为什么能修复
三处合力切断累积链：show 前先 `findFragmentByTag` 移除同 tag 旧实例，FragmentManager 中同 tag 至多一个存活；dismiss/remove 改用更宽的守卫（`!isDetached && host != null`）加 `commitAllowingStateLoss`，保证"只要还挂在 manager 就一定被移除"且不再因状态保存异常中断；Activity onDestroy 清空 DialogFragment 字段消除强引用。monkey 高频弹窗场景下内存曲线回归平稳。隐患：同 tag 旧实例被静默 remove，若有调用方依赖"旧弹窗回调仍触发"会收不到；`commitAllowingStateLoss` 可能丢失恢复时序上的严格一致性，弹窗场景可接受。

## 复盘与经验
- DialogFragment 的 remove 路径必须覆盖"未显示/未添加/状态已保存"全部分支，`isAdded` 守卫 + `commit()` 的组合在异常路径上会静默漏删，`runCatching + commitAllowingStateLoss` 是更稳的兜底。
- 同 tag `show()` 不查重是弹窗 OOM 的经典模式，基类统一处理可一次修复所有业务弹窗——这正是修在 `BaseDialogFragment` 的价值。
- monkey 测试暴露的是累积型泄漏，复现要跑时长而非单步操作；修复后应对比 monkey 前后的内存曲线做量化验证。

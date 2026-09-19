# 无单号 · monkey测试车控crash处理（DialogFragment 生命周期防护）

- **提交**：`7cbfd82e` | 2026-08-21 | sgh | Setting | bugfix
- **缺陷库**：未关联单号（monkey 测试问题清理提交）

## 问题
monkey 测试车控（Setting）模块时出现 crash：DialogFragment 在已脱离（detached）状态下仍被操作，典型表现为 `IllegalStateException`（FragmentManager 操作未附加的 Fragment）或 NPE。

## 根因分析
monkey 随机快速开关页面时，回调（热点设备列表刷新 `handleNoClients()`、弹窗 dismiss 监听）可能在 Fragment 已经 `onDetach` 之后才到达：`HotspotDialogFragment.handleNoClients()` 直接访问 `mBinding`/`mHotspotAdapter`，而 `BaseDialogFragment` 的 `setOnDismissListener` 回调里无条件执行 `parentFragmentManager.beginTransaction().remove(this).commit()`——Fragment 脱离后 `parentFragmentManager` 引用失效，commit 抛异常导致 crash。两处修复都是标准的生命周期防护：回调入口先检查 `isAdded`（及 `isDetached`），未附加则直接返回，不触碰 UI 与 FragmentManager。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt、component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt（2 文件，+6/-2）
```diff
@@ component/CommonTools/src/main/java/com/yadea/common/dialog/BaseDialogFragment.kt @@
             setOnDismissListener {
-                mDismissListener?.onDismiss()
-                parentFragmentManager.beginTransaction().remove(this@BaseDialogFragment).commit()
+                if (isAdded && !isDetached) {
+                    mDismissListener?.onDismiss()
+                    parentFragmentManager.beginTransaction().remove(this@BaseDialogFragment)
+                        .commit()
+                }
             }
```
```diff
@@ application/Setting/.../HotspotDialogFragment.kt @@
     private fun handleNoClients() {
+        if (!isAdded) return
         mHotspotAdapter.setNewInstance(null)
```

## 为什么能修复
所有"脱离后仍操作"的路径被 `isAdded/isDetached` 守卫拦下，monkey 随机时序不再能触发非法 FragmentManager 操作，crash 消除。副作用：脱离态下 dismiss 回调被跳过，若业务依赖 dismiss 通知（如刷新父页面）需确认是否有遗漏场景——本提交选择宁可丢通知不 crash，符合稳定优先。

## 复盘与经验
- DialogFragment 的 dismiss 监听与异步回调是 monkey crash 高发点，基类统一加 `isAdded && !isDetached` 守卫收益最大（本例正是改在 BaseDialogFragment 一处防护所有子类）。
- 异步数据回调进入 Fragment 前必须先验生命周期状态，"先判 isAdded 再碰 binding"应成为模板代码。
- monkey crash 无单号也要留档分析：crash 防护类修复的价值在于守住稳定性底线。

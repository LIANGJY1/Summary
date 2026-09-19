# 无单号 · 优化热点开关状态

- **提交**：`563e3e9d` | 2026-08-21 | dufan | Setting | feature（实质为状态同步缺陷修复）
- **关联单**：无

## 问题（diff 定性）
热点开关（HotspotDialogFragment）的 UI 状态与热点实际状态不同步：广播回调里直接写 `isChecked` 触发不必要的 onCheckedChanged 流程，且原有 resetCancel 每次都强制把开关拨回 true，造成"关热点后开关弹回"的错乱表现。

## 根因分析
1. `WIFI_AP_STATE_DISABLED/ENABLED` 回调直接 set 开关值，与用户手势产生竞争；
2. `resetCancel()` 无条件 `isChecked = true` + 短暂 `mIsCancel=true` 抑制回调，没有判断目标状态是否已经一致，导致关闭动作被撤销。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
             WIFI_AP_STATE_DISABLED -> {
-                mBindingHeader.switchHotspot.isChecked = false
-                mBindingHeader.switchHotspot.disableOverlay()
+                resetCancel(false)
             }
             WIFI_AP_STATE_ENABLED -> {
-                mBindingHeader.switchHotspot.isChecked = true
-                mBindingHeader.switchHotspot.disableOverlay()
+                resetCancel()
             }
...
-    private fun resetCancel(){
+    private fun resetCancel(isCheck: Boolean = true){
+        mBindingHeader.switchHotspot.disableOverlay()
         lifecycleScope.launch{
-            mIsCancel = true
-            mBindingHeader.switchHotspot.disableOverlay()
-            mBindingHeader.switchHotspot.isChecked = true
-            delay(100.milliseconds)
-            mIsCancel = false
+            if (mBindingHeader.switchHotspot.isChecked != isCheck) {
+                mIsCancel = true
+                mBindingHeader.switchHotspot.isChecked = isCheck
+                delay(100.milliseconds)
+                mIsCancel = false
+            }
         }
```
另两处小改：开热点前 delay 100ms→200ms（等待前置状态就绪）；SoundViewModel 日志变量 `soundId`→`sampleId` 修正误打印。

## 为什么能修复
`resetCancel(isCheck)` 把目标状态参数化，且先比对 `isChecked != isCheck` 再动作：状态一致时完全跳过，既不触发多余回调也不再覆盖用户的"关闭"操作；`disableOverlay` 提前到协程外同步执行，保证回调期间开关不可点，消除竞态窗口。

## 复盘与经验
- 可复用手法："set 前先比对"是消除双向绑定状态抖动（UI↔广播回环）的最低成本方案，比 mIsCancel 抑制标志更根本。
- `mIsCancel` 标志 + delay 的抑制窗口仍是脆弱设计（100ms 是拍的），更稳的做法是开关只反映状态、点击只发命令。
- 上车机热点开启链路慢（投影热点），100ms→200ms 这类时序魔数宜集中注释说明依赖的系统广播顺序。

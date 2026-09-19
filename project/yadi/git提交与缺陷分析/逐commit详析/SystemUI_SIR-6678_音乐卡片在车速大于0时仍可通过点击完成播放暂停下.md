# SIR-6678 · 行驶禁触状态下 Dock 音乐卡片仍可点击
- **提交**：`47d85532` | 2026-08-28 | ljl | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车速大于 0 触发行驶触屏锁定（DriveTouchLock）后，主界面交互已被禁用，但底部导航栏（Dock）的音乐卡片仍可点击，能完成播放/暂停/下一首/切歌等媒体操作，违反行驶安全交互规约。

## 根因分析
行驶禁触的状态由 `PageStateMachine.onDriveTouchLockChanged(locked)` 统一分发：置位 `ctx.isScreenLocked`、通过 `notifier.notifyDriveTouchLock(locked)` 通知主交互页面并弹 toast。但该通知链路没有覆盖 Dock 栏——`NavBarFragment` 从未收到锁定状态，其音乐卡片视图 `llMusicCard`、`playBtn`、`next`、`previousBtn` 的 `OnClickListener`/`OnTouchListener` 也没有任何档位或锁定状态守卫，因此车速>0 时点击事件照常进入 `sendMediaAction(...)`。本质是"禁触状态"这一全局安全状态缺少对 Dock 组件的分发与消费。

## 关键代码修改
改动文件：PageStateMachine.kt、NavBarActor.kt、NavBarFragment.java
```diff
--- a/.../digitalkey/mainaction/PageStateMachine.kt
@@ onDriveTouchLockChanged
         ctx.isScreenLocked = locked
         notifier.notifyDriveTouchLock(locked)
+        notifyNavBarDriveTouchLock(locked)
...
+    private fun notifyNavBarDriveTouchLock(locked: Boolean) {
+        mainHandler.post {
+            try {
+                (ActorController.getInstance()[ActorController.TYPE_NAV_BAR] as NavBarActor)
+                    .updateDriveTouchLock(locked)
+            } catch (e: Exception) {
+                LogUtils.e(TAG, "Failed to update nav bar drive touch lock", e)
+            }
+        }
+    }
```
```diff
--- a/.../navbar/ui/NavBarFragment.java
@@ onClick（四个媒体入口逐一加守卫）
             case R.id.pause_button:
+                if (mDriveTouchLocked) {
+                    return;
+                }
                 if (!ViewUtilsKt.isInvalidClick(view)) {
                     sendMediaAction(mPlayPauseActionIndex);
                 }
...
@@ 锁定时禁用控件
+    private void applyMediaControlLock() {
+        if (mDriveTouchLocked) {
+            setMediaControlLocked(playBtn);
+            setMediaControlLocked(next);
+            setMediaControlLocked(previousBtn);
+            setMediaControlLocked(llMusicCard);
+        } else {
+            llMusicCard.setClickable(true);
+            ...
+            updateMediaControlState();
+        }
+    }
+    private void setMediaControlLocked(View view) {
+        view.setClickable(false);
+        view.setEnabled(false);
+        view.setAlpha(0.3f);
+    }
```
另在 `mMediaButtonTouchListener` 首行加 `if (mDriveTouchLocked) return true;`，Fragment 创建时通过 `syncDriveTouchLock()` 读取 `DriveTouchLockController.isLocked()` 同步初始态，避免"先锁后建 Fragment"漏同步。

## 为什么能修复
双向消除根因：一是补全状态分发链（PageStateMachine → ActorController → NavBarActor → NavBarFragment），锁定变化实时推送；二是双保险落地——事件级守卫（onClick/onTouch 直接 return）加上视图级禁用（clickable/enabled=false、alpha 0.3 给用户可见反馈）。初始态同步保证时序正确。隐患：解锁恢复时直接置 clickable=true 再走 `updateMediaControlState()`，若未来解锁瞬间与媒体状态刷新竞争，可能出现短暂状态闪烁，但当前 `updateMediaControlState()` 入口也先检查 `mDriveTouchLocked`，逻辑自洽。

## 复盘与经验
- 安全类全局状态（车速禁触）必须显式枚举所有可交互组件并逐一核对，新增 UI 组件时要接入状态分发，否则必然漏。
- 事件守卫 + 视图禁用双保险：仅 setClickable(false) 可能被后续状态刷新覆盖，onClick 内再判断一层的兜底成本极低。
- 组件（如 Fragment）创建晚于状态变化是常见时序坑，创建时必须主动 pull 一次当前状态（syncDriveTouchLock），不能只依赖 push。

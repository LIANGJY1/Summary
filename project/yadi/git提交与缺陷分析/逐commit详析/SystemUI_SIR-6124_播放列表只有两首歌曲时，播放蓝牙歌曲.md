# SIR-6124 · 播放列表只有两首歌时点下一首无法切换

- **提交**：`0b86536c` | 2026-08-23 | liujinfeng | SystemUI | bugfix（删除2行的最小修复）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
播放列表只有两首歌曲时播放蓝牙音乐，点击底部状态栏媒体卡片的"下一首"按键，曲目看似切换了实际又回到当前曲（两次 next 绕了一圈），无法切换成功。

## 根因分析
缺陷库根因："重复触发 action"。`NavBarFragment` 的媒体按钮同时挂了 `OnTouchListener`（`mMediaButtonTouchListener`，处理通知里带 CarPlay 风格 DOWN/UP Action 的场景，处理后 `return true` 消费事件）和 `OnClickListener`（发送标准 Action 的 `PendingIntent`）。旧代码在 touch 监听的 ACTION_UP 分支末尾写了一行"没有 touch action 时回退到 click 行为"的 `v.performClick()`。问题在于：当 `hasTouchActions` 为 false 时 ACTION_DOWN 已 `return false` 未消费，Android 框架本身就会在 UP 时自动回调 `performClick()`；touch 监听里再手动调一次 `performClick()`，同一次点击把"下一首"Action 的 PendingIntent 发送了两次——两首歌的列表里连切两次又回到原曲，表现为按键无效。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java（-2）
```diff
@@ application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java @@
         } else if (event.getAction() == MotionEvent.ACTION_UP) {
             if (hasTouchActions) {
                 sendPendingIntent(upIndex);
                 return true;
             }
-            // 没有 touch action，回退到 click 行为
-            v.performClick();
         }
         return false;
```

## 为什么能修复
删除手动 `performClick()` 后，无 touch action 场景的 click 回调只由框架的常规触摸分发触发一次，"下一首" Action 每次点击只发送一次，两首歌列表也能正常切换。回退行为本身没有丢——框架自动 performClick 已覆盖。无副作用；这也说明当初的"回退"代码是对 View 触摸-点击分发机制的误解。

## 复盘与经验
- 同时使用 OnTouchListener 和 OnClickListener 时，必须先想清楚"谁负责触发 click"：onTouch 返回 false 时框架会自动 performClick，手动再调一次就是双触发。
- "按键无效"类问题在环形结构（两首歌、两个状态的开关）上最先暴露：双触发绕一圈回到原点，容易被误判为"没反应"。
- 复盘此类 bug 的钥匙是事件流图：一次用户点击在 touch/click 两套监听里各产生了几次业务 Action，画出来一目了然。

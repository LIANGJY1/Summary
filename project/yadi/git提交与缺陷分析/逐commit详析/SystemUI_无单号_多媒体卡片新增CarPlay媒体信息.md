# SIR-XXX · 多媒体卡片新增 CarPlay 媒体信息

- **提交**：`eb905053` | 2026-07-27 | liujinfeng | SystemUI | feature
- **关联单**：SIR-XXX（占位号，未填真实单号）

## 需求/目标
底部导航栏多媒体卡片支持 CarPlay 音源：CarPlay 场景下媒体通知的播放/下一曲按钮是 DOWN/UP 成对 Action（模拟触摸），点击卡片与按钮需要改走通知携带的 PendingIntent，而非固定拉起本机音乐 App。

## 实现结构
3 个文件（+203/-19）：
- `navbar/ui/NavBarFragment.java`：
  - `bindMediaActions` 新增 4 个索引 `mPlayPauseDownActionIndex/mPlayPauseUpActionIndex/mNextDownActionIndex/mNextUpActionIndex`，遍历通知 actions 时优先按"标题匹配 + 含 down/up"识别 CarPlay 触摸对，再匹配标准 previous/playPause/next；
  - 新增 `mMediaButtonTouchListener`：ACTION_DOWN/UP 命中触摸对则 `sendPendingIntent(downIndex/upIndex)` 并消费事件，无触摸对时 ACTION_UP 回退 `performClick()` 走原有标准 Action 路径；
  - 新增 `sendPendingIntent(actionIndex)`：从 `mCurrentMediaEntry` 取 `actions[i].actionIntent` 并 `pi.send()`，越界/空值安全；
  - `handleMusicCardClick`：优先 `notification.contentIntent`（`musicPending`）发送，为空回退 `goAppByPkg(musicPkg)`；`updateMediaInfo` 记录 `musicPending` 与来源包名 `musicPkg`（默认 `SysUIConfig.MUSIC_PACKAGE_NAME`），清空媒体卡片时一并复位；
  - playBtn/next 按钮追加 `setOnTouchListener`；
- `notification-app-integration.md`（+108）：同步更新媒体通知接入文档，说明 CarPlay DOWN/UP Action 契约；
- `common/utils/LogUtils.java`：2 行日志微调。

数据流：CarPlay 媒体通知 → 通知监听 → `bindMediaActions`（识别标准/触摸 Action 索引）→ 按钮 touch → `sendPendingIntent` → CarPlay 侧响应；卡片点击 → contentIntent → 拉起 CarPlay 媒体界面。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
+        // 先匹配 CarPlay touch actions（title 含 "down"/"up"）
+        if (matchesPlayPause(normalizedTitle) && normalizedTitle.contains("down")) {
+            mPlayPauseDownActionIndex = i;
+            continue;
+        }
+        if (matchesPlayPause(normalizedTitle) && normalizedTitle.contains("up")) {
+            mPlayPauseUpActionIndex = i;
+            continue;
+        }
```
```diff
+        if (event.getAction() == MotionEvent.ACTION_DOWN) {
+            if (hasTouchActions) {
+                sendPendingIntent(downIndex);
+                return true;
+            }
+        } else if (event.getAction() == MotionEvent.ACTION_UP) {
+            if (hasTouchActions) {
+                sendPendingIntent(upIndex);
+                return true;
+            }
+            // 没有 touch action，回退到 click 行为
+            v.performClick();
+        }
+        return false;
```
实现讲解：CarPlay 的媒体控制通过通知 Action 的 DOWN/UP 成对 PendingIntent 模拟按下/抬起，本实现不改通知源，只在消费端按标题约定识别并改用 touch 分发，未识别时完全回退到既有 click 行为，兼容在线音乐与 CarPlay 双音源。点击卡片改用 contentIntent 让"打开媒体源"也遵循通知携带的意图。

## 复盘与要点
- 可复用手法："通知 Action 契约适配器"——按 title 约定把非标 Action（down/up 对）映射为触摸事件，消费端兼容两种协议，无需改动通知发布方。
- 风险：以英文 "down"/"up" 子串识别 CarPlay Action 是隐式契约，通知源改标题即失效；文档 `notification-app-integration.md` 同步更新是该契约的正确固化方式，建议再加常量校验。
- `musicPkg/musicPending` 在清空媒体时复位为默认值，防止残留 PendingIntent 指向已退出的 CarPlay 会话，细节处理到位。

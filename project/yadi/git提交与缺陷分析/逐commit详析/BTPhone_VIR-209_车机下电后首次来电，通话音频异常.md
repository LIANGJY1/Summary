# VIR-209 · 车机下电后首次来电扬声器无声音（音频路由设置过早）

- **提交**：`944ee915` | 2026-07-21 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联单号（缺陷库无记录）

## 问题
车机下电后重新上电、收到首次蓝牙来电时，通话音频异常，扬声器无声音输出。

## 根因分析
`FloatCallWindowPresenter.java` 处理来电（INCOMING）状态时，用一个 `Handler.postDelayed` 延时任务设置音频路由：`uiCallManager.setAudioRoute(ROUTE_SPEAKER)`。原延时只有 300ms。车机下电后首次来电是极端时序：蓝牙协议栈/电话服务刚从冷启动恢复，"蓝牙的状态来不及同步"（提交原文），300ms 内蓝牙音频通路尚未就绪，此时把路由切到 `ROUTE_SPEAKER` 的调用落在未就绪的通路上，路由设置实际未生效或被丢弃，导致扬声器无声。后续来电因蓝牙已同步完成而正常，故表现为"仅下电后首次来电"。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindowPresenter.java
@@ setAudioRoute 延时任务
                     LogUtils.d(TAG, "20@setAudioRoute: " + ROUTE_SPEAKER);
                     uiCallManager.setAudioRoute(ROUTE_SPEAKER);
                 }
-            }, 300);
+            }, 800);
```

## 为什么能修复
把路由设置从来电后 300ms 推迟到 800ms，给蓝牙协议栈与音频服务留出状态同步时间，`setAudioRoute(ROUTE_SPEAKER)` 执行时通路已就绪，扬声器恢复出声。这是典型的"以延时换时序"的工程修法：改动极小、见效直接，但本质是经验值而非事件驱动——若冷启动更慢（如 800ms 仍不够），问题会按概率复现；副作用是正常场景下路由切换推迟了 500ms，用户基本无感。

## 复盘与经验
- "仅首次/仅冷启动复现"的 bug，优先怀疑依赖服务的初始化时序，而不是功能逻辑本身。
- 用固定延时对齐异步系统状态是脆弱方案，更稳的做法是监听蓝牙/音频通路的就绪回调再设路由；在无法拿到就绪通知时，延时兜底 + 日志观测是可接受的过渡。
- 延时值应配合日志（此处保留 `20@setAudioRoute` 日志）持续验证，复现率上升时及时调整为事件驱动。

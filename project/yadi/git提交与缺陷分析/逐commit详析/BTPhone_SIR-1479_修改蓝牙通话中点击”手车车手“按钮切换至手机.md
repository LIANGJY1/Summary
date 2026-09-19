# SIR-1479 · 通话转手机后车机麦克风未静音
- **提交**：`5e22f25b` | 2026-07-02 | duanlonglong | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙通话中点击"手车/车手"按钮把通话切到手机后，车机和手机都能收到声音（车机麦克风仍然拾音），对方听到回声/双通道。

## 根因分析
通话音频路由切换逻辑在悬浮通话窗 `FloatCallWindow`（`application/BTPhone/.../btphone/floatview/FloatCallWindow.java`，约 654 行）中：`targetRoute == ROUTE_BLUETOOTH` 表示音频走车机（toast "to_car"），else 分支表示切到手机（toast "to_phone"）。旧代码切换路由后只弹 toast，没有同步车机端麦克风静音状态——切到手机后车机麦克风依旧开启拾音，两条链路同时出声。缺陷库归因"扬声器切到手机时未静音车机的麦克风"。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
@@ -654,8 +654,10 @@
         if(targetRoute == ROUTE_BLUETOOTH){
             ToastUtils.INSTANCE.showMsgToast(mContext,mContext.getString(R.string.to_car));
+            updateMuteButton(false,uiCallManager);
         }else {
             ToastUtils.INSTANCE.showMsgToast(mContext,mContext.getString(R.string.to_phone));
+            updateMuteButton(true,uiCallManager);
         }
```

## 为什么能修复
路由切换与麦克风静音状态绑定：切到手机（else 分支）立即 `updateMuteButton(true, uiCallManager)` 静音车机麦克风，声音只从手机链路送出；切回车机（ROUTE_BLUETOOTH）时 `updateMuteButton(false, ...)` 解除静音恢复拾音。路由与麦克风状态不再可能处于矛盾组合。隐患：如果用户切到手机前已手动静音，切回车机会被强制解除静音，静音状态被路由切换覆盖，需产品确认是否符合预期。

## 复盘与经验
- 音频路由与麦克风状态是强耦合的状态对，切换路由时必须成对更新，否则出现"双通道拾音"这类体验缺陷。
- 悬浮窗/多窗口通话 UI 与 `InCallServiceImpl` 等服务层各自操作通话状态时，路由切换的副作用（静音、录音）要在统一的交互入口处理。

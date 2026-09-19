# 无单号 · 锁屏页面解锁逻辑优化（开机事件放行 + 解锁后只恢复状态栏导航栏）

- **提交**：`88c44f0f` | 2026-07-06 | ljl | SystemUI | feature
- **关联单**：无

## 需求/目标
优化数字钥匙 PIN 锁屏（KeyguardActor）的解锁链路：开机完成事件不再被"锁屏中"拦截；解锁后恢复系统 UI 时不再拉起下拉面板，只恢复导航栏和状态栏；密码键盘从 `Button` 换 `TextView` 并调整配色。

## 实现结构
- 修改 `digitalkey/mainaction/common/GestureGuard.kt`：事件拦截白名单——锁屏显示时除 `Event.AndroidBootCompleted` 外全部忽略，保证开机事件能驱动系统初始化流程。
- 修改 `init/ActorController.kt`：新增 `showSystemUIAfterUnlock()`，只 `show()` NavBar 与 StatusBar 两个 Actor。
- 修改 `keyguard/actor/KeyguardActor.kt`：数字/删除/确认键 `findViewById<Button>` 全部改 `findViewById<TextView>`（含 `setPinInputEnabled`）；`showSystemUI()` 改调新的 `showSystemUIAfterUnlock()`。
- 修改 `res/layout/actor_keyguard.xml`：键盘所有 `Button` 节点替换为 `TextView` 并删除 `borderlessButtonStyle`；删一条注释。
- 修改 `bg_keyguard_key_delete.xml` / `bg_keyguard_key_pressed.xml`：按键底色 `#D1D7E6` → 半透明灰蓝 `#CC868E9A`。

数据流：开机广播 `AndroidBootCompleted` → `GestureGuard.isBlocked` 放行 → 事件框架继续；PIN 验证成功 → `KeyguardActor.showSystemUI()` → `ActorController.showSystemUIAfterUnlock()` → 仅 NavBar/StatusBar 显示，下拉面板保持隐藏。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/digitalkey/mainaction/common/GestureGuard.kt
@@ -20,7 +20,7 @@ object GestureGuard {
         // KeyGuard 锁屏显示中，不响应事件
         try {
-            if (ActorController.getInstance()[ActorController.TYPE_KEYGUARD].isShow()) {
+            if (ActorController.getInstance()[ActorController.TYPE_KEYGUARD].isShow() && event != Event.AndroidBootCompleted) {
                 LogUtils.d(TAG, "Keyguard is showing, ignore event=${event.name}")
                 return true
             }
```

```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/init/ActorController.kt
@@ -80,6 +80,14 @@
+    /**
+     * 解锁后恢复系统UI显示（不拉起下拉面板）
+     */
+    fun showSystemUIAfterUnlock() {
+        mActorMap[TYPE_NAV_BAR]?.show()
+        mActorMap[TYPE_STATUS_BAR]?.show()
+    }
```

实现讲解：车机锁屏与手机不同——锁屏时系统仍需响应开机事件完成自启装配，旧逻辑"锁屏中一律拦截"会让开机事件被吞，导致解锁后系统状态不完整。解锁恢复走新的细粒度方法，避免把下拉面板一起带出来（安全上防止不解锁就能下拉操作车机）。`Button`→`TextView` 是去系统默认按钮样式/水波纹、配合半透明底色实现新 UI 的常规做法，代码侧 `findViewById` 泛型同步改，避免 ClassCastException。

## 复盘与要点
- 事件"全局拦截器 + 白名单"模式：拦截器新增时必须考虑系统级必要事件（开机、无障碍等），否则锁屏会变成事件黑洞——本次就是补 `AndroidBootCompleted` 白名单的修正。
- "恢复系统 UI"拆成全量/部分两个 API（`showSystemUI` vs `showSystemUIAfterUnlock`），比在调用点挑 Actor 更能表达安全语义，可复用到其他 Actor 场景。
- 按键底色用 8 位 HEX（带 alpha）而非 color 资源，与 Setting 圆角提交同款问题：视觉常量散落 drawable，主题化程度低。

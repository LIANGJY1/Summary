# 无单号 · CarPlay 通话悬浮窗页面开发

- **提交**：`a3bb821d` | 2026-07-29 | ljl | BTPhone | feature
- **关联单**：无（建议测试范围：CP 不在前台情况下收到 CP 通话）

## 需求/目标
当 CarPlay 投屏不在前台时收到 CarPlay 通话，在系统层弹出通话悬浮卡片（单路/双路），支持接听、挂断、保留、双路切换，补齐蓝牙电话模块对 CarPlay 通话的兜底交互。

## 实现结构
新增 10 个文件（+1307 行）：
- `CarPlayCallManager.java`（441 行，单例）：绑定创达 `ts-platform-library` 的 `ICarPlayAppManager`（500ms×6 次重试，参考 Launcher DeviceConnectManager 模式）；注册通话状态/投屏前后台/通信信息三类监听；内部 `LinkedHashMap<callUuid, CallStateInfo>` 维护通话列表（DISCONNECTED/UNKNOW 视为结束）
- `CarPlayCallWindow.java`（290 行）：`TYPE_APPLICATION_OVERLAY` 悬浮窗，顶部居中 63dp，卡片宽 450dp；单路只渲染下卡，双路上卡=HELD 保留路（带 swap 按钮）、下卡=当前通话
- `CarPlayTestActivity.java`（295 行）+ `activity_carplay_test.xml`：adb 启动的模拟测试页，支持前台/后台状态覆盖（`mForegroundOverride`）
- 布局：`carplay_call_window.xml`、`carplay_call_card.xml`、drawable `bg_carplay_call_card.xml`
- `BtPhoneApp.onCreate` 调用 `init()`；`build.gradle` 以 `compileOnly` 引入 jar；manifest 声明 `uses-library ts.platform.library required=false` + `car.permission.CARPLAY_APP`

数据流：SDK binder 回调 → post 主线程 → 更新 `mCallMap`/前台标志 → `refreshWindow()` → WindowManager 增删悬浮卡片。

## 关键代码
```diff
--- a/application/BTPhone/build.gradle
+++ b/application/BTPhone/build.gradle
@@ -100,6 +100,8 @@
+    // CarPlay 通话弹窗：创达 ts-platform-library.jar，运行时类由系统共享库 ts.platform.library 提供（见 manifest uses-library，required=false）
+    compileOnly files('../../component/commonlibs/gestureconnectivity/ts-platform-library.jar')
```
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java
@@ -0,0 +1,290 @@
+    private void render(Map<String, CallStateInfo> callMap) {
+        List<Map.Entry<String, CallStateInfo>> calls = new ArrayList<>(callMap.entrySet());
+        if (calls.size() >= 2) {
+            // 双路通话 I1-6：上卡=保留路（HELD，带切换按钮），下卡=当前通话路
+            Map.Entry<String, CallStateInfo> held = findByStatus(calls, CallStateInfo.CALL_STATE_HELD);
+            Map.Entry<String, CallStateInfo> current = findCurrent(calls, held);
+            mCardTop.setVisibility(View.VISIBLE);
+            renderCard(mCardTop, held != null ? held : calls.get(0), true);
+            renderCard(mCardBottom, current, false);
+        } else {
+            // 单路通话 I1-1 ~ I1-5：只显示下卡
+            mCardTop.setVisibility(View.GONE);
+            renderCard(mCardBottom, calls.get(0), false);
+        }
+    }
```
实现讲解：核心约束是"compileOnly + uses-library required=false + 全 SDK 访问 try/catch Throwable（含 NoClassDefFoundError）"，保证无该共享库的车机上蓝牙电话主功能零影响。渲染规则把 UX 稿 I1-1~I1-6 状态映射成卡片组合；按钮点击只调 SDK 接口不改 UI，等回调回流刷新，形成单向数据流。

## 复盘与要点
- 可复用手法：可选系统共享库的标准接入三件套（compileOnly jar + uses-library required=false + Throwable 兜底），适用于依赖车厂平台库的差异化功能。
- 服务绑定重试（MAX_RETRY_COUNT=6 / 500ms）与断连后清态+重绑，是从 Launcher DeviceConnectManager 抄来的成熟模式，保证服务冷启动竞态下仍能连上。
- 单例 Manager + 独立 Window 类职责分离清晰；Manager 还带 `mForegroundOverride` 测试钩子，无 CP 环境也能全状态回归。

# SIR-8231 · monkey 后状态栏左下角消失、界面整体上移
- **提交**：`9f0cb81c` | 2026-09-18 | caohongliang | SystemUIService | bugfix
- **缺陷库**：等级 B · 频次 偶现-低于10% · 状态 待测试验证 · 域 系统需求

## 问题
运行 monkey 脚本后偶现：底部状态栏左下角消失，车机界面位置整体往上偏移。

## 根因分析
缺陷库 rc：`windowmanager 拿的 context 是 display2 的，导致状态栏和 dock 添加到 display2 上了`。`StatusBarWindowManager`/`NavBarWindowManager` 初始化时用 `mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager` 直接取 WindowManager——该 WindowManager 绑定的 Display 取决于传入 Context 携带的 display 信息。monkey 压测下进程/Context 状态被打乱，偶发拿到携带错误屏幕信息（display2）的 Context，状态栏、导航栏窗口被添加到副屏上，主屏自然"左下角消失、界面偏移"。这是多屏车机上"Context 与 Display 隐式绑定"的典型坑。

## 关键代码修改
改动文件：BasicWindowManager.kt、StatusBarWindowManager.kt、NavBarWindowManager.kt
```diff
// component/SystemUIService/src/main/java/com/android/ext/systemuiservice/base/BasicWindowManager.kt
+    protected fun getWindowManager(context: Context, displayId: Int): WindowManager {
+        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
+        val display = displayManager.getDisplay(displayId)
+        if (display == null) {
+            Log.w(TAG, "Target display is unavailable, fallback to context WindowManager: displayId=$displayId")
+            return context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
+        }
+        Log.i(TAG, "WindowManager initialized for displayId=${display.displayId}")
+        return context.createDisplayContext(display)
+            .getSystemService(Context.WINDOW_SERVICE) as WindowManager
+    }
```
```diff
// component/SystemUIService/src/main/java/com/android/ext/systemuiservice/statusbar/manager/StatusBarWindowManager.kt
     private fun initWindowManager() {
-        mWindowManager = mContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
+        mWindowManager = getWindowManager(mContext, Display.DEFAULT_DISPLAY)
     }
```
（NavBarWindowManager.kt 同样改为一行 `getWindowManager(mContext, Display.DEFAULT_DISPLAY)`）

## 为什么能修复
基类新增 `getWindowManager(context, displayId)` 工具方法：通过 `DisplayManager.getDisplay(Display.DEFAULT_DISPLAY)` 显式取主屏 Display，再用 `createDisplayContext(display)` 构造绑定主屏的 Context，最后从该 Context 取 WindowManager——状态栏/导航栏窗口从此确定添加到 display0，不再受传入 Context 携带的错误屏幕信息影响。display 不可用时回退原逻辑并打日志，保证健壮性。副作用：若未来需要把系统栏投到副屏，需显式改参数；display0 短暂不可用时的回退路径仍可能继承原问题，但有日志可查。

## 复盘与经验
- 多屏设备上 `context.getSystemService(WINDOW_SERVICE)` 得到的 WindowManager 绑定的是 Context 关联的 Display，系统级窗口（状态栏/导航栏）必须用 `createDisplayContext(display)` 显式钉住目标屏。
- monkey/压测暴露的偶现问题常源于对隐式环境依赖（Context 携带的 display、user、configuration）的信任；关键系统组件应显式声明依赖。
- 兜底要带日志与降级路径（display null → fallback + Log.w），既保住功能又不丢现场信息。
- 缺陷域归为"系统需求"、由 App 侧做兜底（缺陷库 sol："App 这边 systemui 针对该问题做了兜底处理"），说明底层 framework 问题在应用层以防御式编码对冲是常见务实做法。

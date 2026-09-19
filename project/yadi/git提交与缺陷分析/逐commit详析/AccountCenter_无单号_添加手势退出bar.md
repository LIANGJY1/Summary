# 无单号 · [SRS_UserCenter_007] 添加手势退出 bar

- **提交**：`16fce0aa` | 2026-08-18 | liqingqing | AccountCenter | feature
- **关联单**：无（SRS_UserCenter_007）

## 需求/目标
给账号中心的"个人中心页（CenterActivity）"与"登录页（LoginActivity）"补齐统一的手势退出交互：顶部居中显示 grabber 把手，用户按住下滑达到阈值后退出应用；同时把退出方式从 `exitProcess(0)` 杀进程改为 `moveTaskToBack + finishAffinity` 的规范退栈，并顺带修正二维码刷新间隔与刷新逻辑。

## 实现结构
改动 6 个文件：新增 `drawable/grabber.xml`（120x6dp 圆角半透明把手矢量图）；`activity_center.xml` 移除旧的 `view_quick_line` 装饰线、在 `LapseTouchLayout` 根容器内加 `grabber` View；`activity_login.xml` 把根布局从 LinearLayout 换成 `LapseTouchLayout` 并加 grabber；`CenterActivity` / `LoginActivity` 实现 `LapseTouchLayout.onLapseDownExitListener`，`onLapseDownExit()` 统一为 `moveTaskToBack(true) + finishAffinity()`；`LoginDialogActivity` 的二维码"点击刷新"改为先停两个定时器再重新请求登录二维码。数据流：触摸事件由公共控件 `LapseTouchLayout` 捕获→判定下滑阈值→回调 Activity 的 `onLapseDownExit`→退栈退出。

## 关键代码
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/center/CenterActivity.kt
     override fun onLapseDownExit() {
         LogUtils.d(TAG,"[GrabberExit] threshold reached, exiting app")
-        exitProcess(0)
+        moveTaskToBack(true)
+        finishAffinity()
     }
```
```diff
--- a/application/AccountCenter/src/main/res/layout/activity_center.xml
+
+        <View
+            android:id="@+id/grabber"
+            android:layout_width="120dp"
+            android:layout_height="6dp"
+            android:layout_marginTop="14dp"
+            android:background="@drawable/grabber"
+            app:layout_constraintEnd_toEndOf="parent"
+            app:layout_constraintStart_toStartOf="parent"
+            app:layout_constraintTop_toTopOf="parent" />
     </com.yadea.common.widgets.LapseTouchLayout>
```
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginDialogActivity.kt
         qrCodeLoginDialog!!.setOnRefreshListener(QrCodeLoginDialog.OnRefreshListener {
-            qrCodeLoginDialog!!.showNormalState()
+            stopRefreshTimer()
+            stopLoginStateRefreshTimer()
+            requstLoginQRCode()
         })
```
实现讲解：退出交互收敛为一个可复用模式——公共容器 `LapseTouchLayout` 负责手势判定，页面只实现回调；把手样式抽成 `grabber.xml` 供两个布局共用。退出语义从"杀进程"换成"回桌面 + 结束本任务栈"，是 Android 车机上更安全的退出姿势（避免账号服务进程被硬杀）。附带把二维码刷新间隔从 90s 修正为注释所述 dev 900s（900000ms），并让手动刷新先停旧定时器防重复轮询。

## 复盘与要点
- 手势退出组件化（容器控件 + 监听接口）让多个页面零成本接入，可复用于其他全屏应用。
- `exitProcess(0)` → `finishAffinity()` 的替换是典型修正：直接杀进程会绕过生命周期、可能中断后台账号/绑定服务。
- LoginActivity 刷新时间注释（"dev 900 秒，test 90 秒"）与硬编码值仍有出入，环境差异应配置化而不是改代码。

# 无单号 · AccountCenter UI及bug修改（登录页定时器泄漏修复 + 视觉调整）

- **提交**：`b0e79ba4` | 2026-07-22 | hedeyuan | AccountCenter | bugfix（UI+逻辑混合提交）
- **缺陷库**：未关联单号

## 问题
杂项修复提交：登录页二维码/登录状态刷新定时器在页面不可见后仍持续轮询，二维码弹窗残留；另有登录头像用错图、账号中心缺提示图标、退出登录弹窗按钮偏小等 UI 问题。

## 根因分析
`LoginActivity.kt` 启动了两个周期定时器（`refleshTime` 刷新二维码、`loginStaterefleshTime` 轮询登录状态），但此前没有任何生命周期出口去停止它们——页面退到后台甚至销毁后定时器仍在跑，既白耗电，又可能在页面不可见时触发刷新逻辑；`qrCodeLoginDialog` 也不随页面离开而关闭，产生悬浮残留。另外 `refleshTime` 从 900000ms（900 秒）改为 90000ms（90 秒），与注释"test环境刷新时间90秒"对齐，属于环境参数调整。其余为资源类改动：`app_icon.png` 二进制更新（31354→26996 字节），登录页头像从 `@mipmap/login_nomal_user_icon` 换成 `@drawable/app_icon`，新增 `info.xml` 提示图标，退出登录弹窗按钮 48dp→74dp（车机大按钮规范）。

## 关键代码修改
改动文件：application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt；application/AccountCenter/src/main/res/layout/activity_login.xml；application/AccountCenter/src/main/res/layout/activity_center.xml；application/AccountCenter/src/main/res/layout/dialog_exit_login.xml；application/AccountCenter/src/main/res/drawable/info.xml（新增）；application/AccountCenter/src/main/res/drawable/app_icon.png（二进制更新）
```diff
--- a/application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt
-    private val refleshTime: Long = 900000  //dev环境刷新时间900秒，test环境刷新时间90秒
+    private val refleshTime: Long = 90000  //dev环境刷新时间900秒，test环境刷新时间90秒
+
+    override fun onPause() {
+        super.onPause()
+        // 停止定时器
+        stopRefreshTimer()
+        stopLoginStateRefreshTimer()
+        if (qrCodeLoginDialog != null) {
+            qrCodeLoginDialog!!.dismiss()
+        }
+    }
```

## 为什么能修复
在 `onPause` 统一停掉两个定时器并关闭二维码弹窗，页面不可见即停止轮询，消除后台空转与弹窗残留。隐患：只覆盖了 `onPause`，若定时器在 `onResume` 有对应重启逻辑则没问题，否则从其他页面返回后二维码可能不再自动刷新（需结合 `onResume` 实现确认）；`qrCodeLoginDialog!!` 强解包在极端时序下有 NPE 风险。二进制资源 `app_icon.png` 与 `info.xml`、弹窗尺寸均为视觉对齐，无逻辑风险。

## 复盘与经验
- 周期性 Handler/Timer 必须有对称的生命周期出口（onPause/onDestroy 停止），否则车机端长期驻留场景会持续耗电并触发不可见刷新。
- 车机按钮高度按可达性规范校验（本例 48dp→74dp），触控目标偏小是 UI 走查高频问题。
- "UI及bug修改"式混合提交不利于追溯，尽量把逻辑修复与视觉调整拆成两个提交。

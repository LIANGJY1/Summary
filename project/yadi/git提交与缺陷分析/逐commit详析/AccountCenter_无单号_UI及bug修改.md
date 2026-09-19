# 无单号 · 扫码登录轮询中"加载失败"提示与定时器停止的时序修正

- **提交**：`90338adc` | 2026-07-22 | hedeyuan | AccountCenter | bugfix
- **缺陷库**：未关联单号

## 问题
扫码登录二维码刷新定时器触发时，先弹"加载失败"再停状态轮询，且二维码 ImageView 预置了一张占位图，刷新前会闪现旧的静态二维码图。

## 根因分析
`LoginActivity.refreshRunnable` 原顺序是先 `qrCodeLoginDialog?.showLoadFailState()` 再 `stopLoginStateRefreshTimer()`。在两步之间窗口期内，每秒轮询登录状态的 `loginStaterefreshRunnable` 仍可能再触发一次 `requestLoginState()`/UI 刷新，与"加载失败"状态互相覆盖，出现状态抖动。另外布局 `dialog_qr_code_login.xml` 的 `iv_qr_code` 写死了 `android:src="@mipmap/qrcode"`，真实二维码是异步生成后 `setQrCodeImage` 设置的，在设置完成前用户看到的是一张与真实内容无关的占位二维码，属于误导性展示。

## 关键代码修改
改动文件：`application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt`、`application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml`
```diff
// application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt
     private val refreshRunnable = object : Runnable {  // NOSONAR
         override fun run() {
-            qrCodeLoginDialog?.showLoadFailState()
             stopLoginStateRefreshTimer()
+            qrCodeLoginDialog?.showLoadFailState()
             handler.postDelayed(this, refleshTime)
         }
     }
```
```diff
// application/AccountCenter/src/main/res/layout/dialog_qr_code_login.xml
             android:id="@+id/iv_qr_code"
             android:layout_width="190dp"
             android:layout_height="190dp"
-            android:src="@mipmap/qrcode"
             android:scaleType="centerCrop"/>
```

## 为什么能修复
先停轮询再改 UI，保证"进入失败态"之后不会再有轮询回调来改写对话框状态，消除竞态窗口；去掉 XML 占位图后，`iv_qr_code` 在真实二维码生成前保持空白，不会再展示假的二维码内容。`postDelayed(this, refleshTime)` 续跑逻辑保持不变，二维码 90 秒周期刷新行为不受影响。

## 复盘与经验
- 定时器回调里"改状态 + 停定时器"两步有先后敏感时，应先摘除回调源再改 UI，避免其他回调在中间插入。
- XML 里给异步加载的 ImageView 写死 `src` 占位图是常见误导源：占位内容会被用户当成真实数据，空白占位反而更安全。
- 此类小提交没有关联单号，仅凭 `[what][why][how]` 三句同文难以追溯，提交信息质量本身就是可维护性的一部分。

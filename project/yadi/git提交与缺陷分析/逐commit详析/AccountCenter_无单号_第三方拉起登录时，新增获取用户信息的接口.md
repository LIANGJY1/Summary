# 无单号 · 第三方拉起登录后缺少获取用户信息接口（登录闭环断裂）

- **提交**：`65296f56` | 2026-07-23 | hedeyuan | AccountCenter | bugfix（缺陷修复+接口补全）
- **缺陷库**：未关联单号

## 问题
第三方应用拉起账号中心登录成功后，本地缓存里没有用户信息（userId/手机号），第三方拿不到用户数据；同时二维码弹窗点外部取消后定时器仍在跑、Activity 不退出。

## 根因分析
`LoginDialogActivity.kt` 的登录成功处理 `handleLoginStateResponse` 里，写入 `ACCESS_TOKEN/REFRESH_TOKEN` 后**直接** `startActivity(CenterActivity)` + `finish()`，从未调用用户信息接口，`Constants.CACHE.USER_ID`、`USER_TELEPHONE` 等缓存项无来源——登录闭环缺少"换取用户信息"一步。另外：弹窗点击外部取消（`OnCancelListener`）没有停 `stopRefreshTimer/stopLoginStateRefreshTimer` 也不 `finish()`，产生残留；`LoginActivity` 的"已有 token 直接跳 CenterActivity"判断放在 `initView()`，只在首次创建时执行一次，从协议页等场景返回（onResume）后不会重新判定，跳转时机错误。

## 关键代码修改
改动文件：application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginDialogActivity.kt；application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginViewModel.kt；application/AccountCenter/src/main/java/com/yadea/accountcenter/ui/login/LoginActivity.kt
```diff
--- a/.../login/LoginDialogActivity.kt
@@ handleLoginStateResponse 登录成功
-        val intent = Intent(this, CenterActivity::class.java)
-        intent.putExtra("ACCESS_TOKEN", loginState.accessToken)
-        startActivity(intent)
-        finish()
+        stopRefreshTimer()
+        stopLoginStateRefreshTimer()
+        //请求获取用户信息的接口getUserInfo()
+        viewModel?.getUserInfo()
@@ 新增用户信息回调处理
+    private fun handleUserInfoResponse(response: BaseResponse<UserInfoResponse?>?) {
+        if (response == null || !response.isSuccess) { finish(); return }
+        val userInfo = response.data ?: run { finish(); return }
+        SettingsUtils.setGSetting(Constants.CACHE.USER_ID, userInfo.id)
+        SettingsUtils.setGSetting(Constants.CACHE.USER_TELEPHONE, userInfo.mobile)
+        SettingsUtils.setGSetting(Constants.CACHE.CLEAR_DATA_FLAG, "0")
+        finish()
+    }
@@ 弹窗外部取消
+        qrCodeLoginDialog!!.setDialogCancelListener(QrCodeLoginDialog.OnCancelListener {
+            stopRefreshTimer(); stopLoginStateRefreshTimer(); finish()
+        })
--- a/.../login/LoginViewModel.kt
+    fun getUserInfo() {
+        val disposable = repository.getUserInfo().subscribe(
+            Consumer { response -> userInfoLiveData.setValue(response) },
+            Consumer { throwable -> /* 构造 500 错误 response 写入 userInfoLiveData */ }
+        )
+        addDisposable(disposable)
+    }
--- a/.../login/LoginActivity.kt：token 跳转判断从 initView() 移到 onResume()
```

## 为什么能修复
登录成功后不再直跳个人中心，而是先 `getUserInfo()` 拉取用户信息并落缓存（`USER_ID/USER_TELEPHONE/CLEAR_DATA_FLAG`），补全了"token→用户信息→第三方可读"的闭环；弹窗取消监听器停定时器并 `finish()`，消除残留；token 判断移到 `onResume` 后，任何返回场景都能正确接管跳转。风险：`getUserInfo` 失败时直接 `finish()` 不重试，第三方可能仍拿不到用户信息，需要调用方自行重试登录流程。

## 复盘与经验
- 认证闭环 = 拿 token + 换用户信息 + 落缓存，缺一步下游（尤其第三方拉起场景）就会"登录成功却无数据"；新接登录流程时按闭环清单自查。
- 依赖"当前是否有 token"的跳转判断放 `onResume` 而不是 `initView`，才能覆盖协议页返回、第三方拉起返回等中间态。
- 可取消的弹窗（点外部消失）也要作为一条出口纳入定时器清理路径，`setOnCancelListener`/自定义 `OnCancelListener` 都要接住。

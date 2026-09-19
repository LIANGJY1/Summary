# VIR-134 · 设置页"用户许可协议"点击打不开

- **提交**：`546e6a91` | 2026-07-06 | dufan | Setting | bugfix
- **缺陷库**：未关联单号（提交带 VIR-134 单号，缺陷库 defs 为空）

## 问题
设置→系统 页面点击"用户许可协议"没有任何反应，协议页面打不开。

## 根因分析
`SystemFragment.kt` 中 `mBinding.tvUserLicenseAgreement.setOnFastClickListener { }` 的 lambda **函数体是空的**——点击监听注册了但什么都没做，属于功能占位未实现（对比同文件"个人信息保护政策"入口早已按 `WebFragment + PAGE_TITLE/PAGE_URL` 的套路实现）。顺带修正了另一个入口的资源错位："个人信息保护政策"原来也加载 `user_service_agreement.html`（用户服务协议的 HTML），本次改为加载新增的 `personal_infomation_agreement.html`，两个协议各自对应正确的资产文件。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt`（+11/-1）、`application/Setting/src/main/assets/personal_infomation_agreement.html`（新增，329 行协议正文）
```diff
// --- application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
-            bundle.putString("PAGE_URL", "user_service_agreement.html")   // 个人信息保护政策入口改用专属 HTML
+            bundle.putString("PAGE_URL", "personal_infomation_agreement.html")
             ...
         mBinding.tvUserLicenseAgreement.setOnFastClickListener {
+            val webFragment = WebFragment()
+            val bundle = Bundle()
+            bundle.putString("PAGE_TITLE", getString(R.string.user_license_agreement))
+            bundle.putString("PAGE_URL", "user_service_agreement.html")
+            webFragment.arguments = bundle
+            webFragment.show(childFragmentManager, "WebFragment")
         }
```

## 为什么能修复
给空回调补上了完整的打开流程（构造 `WebFragment`、传入标题与 assets 内 HTML 路径、`show(childFragmentManager)`），点击即有响应；同时两个协议入口的 HTML 资产一一对应，消除了"隐私政策显示服务协议内容"的隐性错位。无副作用，属补齐缺失实现。

## 复盘与经验
- **空的点击回调是"注册了但没实现"的典型静默缺陷**：代码能编译、界面能显示，只有点击才暴露。CI 可加 lint 规则检测空 lambda 监听器，或用"交互入口清单"做走查。
- **资源引用要做映射校验**：两个协议共用一个 HTML 说明资产命名/引用没有对照表，`PAGE_URL` 这类字符串协议（硬编码 assets 文件名）打错或复用都不会报错，建议集中常量化。
- 法务类文案（协议 HTML）应随需求单独立资产文件，避免后续替换时相互覆盖。

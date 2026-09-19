# SIR-2912 · Vlog已连接相机进入功能页后残留引导页，重进应用有概率回到引导页

- **提交**：`f80efe5b` | 2026-07-21 | daizhecheng | Vlog | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车载vlog

## 问题
已连接过运动相机的用户进入应用：后台会多一个引导页面（HomeActivity）；退出应用再进入时，有概率直接回到引导页而不是功能页，且透明主题能透视到上一页面，观感像"多了一层页面"。

## 根因分析
`HomeActivity.kt` 的 `ConnectDeviceEvent` 处理中，检测到 `viewModel.isConnected` 为真时执行 `startActivity(Intent(this@HomeActivity, CameraPairedActivity::class.java))` 跳转功能页，但**没有 `finish()` 自身**。`HomeActivity` 是引导/首页且主题透明（元数据："主题透明可以看见上一个页面"），它留在返回栈底部导致两个后果：一是后台任务列表里多出一个引导页实例；二是应用退出后重进，系统恢复任务栈时先呈现的仍是这个旧的 `HomeActivity` 实例（有概率恢复到引导态），而不是最新的功能页。根因是"跳转后不关闭来源页"这一导航写法与透明主题、单任务栈恢复机制叠加。

## 关键代码修改
改动文件：application/Vlog/src/main/java/com/yadea/vlog/main/ui/HomeActivity.kt
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/HomeActivity.kt
@@ ConnectDeviceEvent 处理
         LogUtils.i(TAG, "  ConnectDeviceEvent  status ==>" + event.status)
         if (viewModel.isConnected) {
             startActivity(Intent(this@HomeActivity, CameraPairedActivity::class.java))
+            finish()
         } else {
             viewModel.connectDeviceByWiFi()
         }
```

## 为什么能修复
跳转到 `CameraPairedActivity` 后立即 `finish()`，把引导页从返回栈中移除：后台不再残留引导页任务项；再次进入应用时栈顶是功能页，不会回到引导页。副作用很小——`HomeActivity` 跳走后本就不再承载交互，`finish()` 不影响 `ConnectViewModel` 的连接流程（连接成功事件已消费）。需注意若 `onCreate` 有依赖 `HomeActivity` 存活的回调，要确认其生命周期安全。

## 复盘与经验
- "跳转后是否 finish 来源页"在透明/半透明主题下尤其关键：透明页不 finish 会在最近任务、返回手势、进程恢复等场景反复露馅。
- 入口页/引导页向主功能页的跳转，默认应视为"单向门"：跳转即销毁，状态由目标页持有。
- 低概率复现类 bug 若与"重进应用"相关，优先排查任务栈残留与 Activity 恢复（savedInstanceState/栈结构），而不是只盯业务逻辑。

# SIR-5896 · iPhone 未授权同步后重进蓝牙电话闪退
- **提交**：`800adf56` | 2026-08-14 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
iPhone 未授权联系人同步时，下滑关闭蓝牙电话再进入，同步蓝牙电话时应用闪退。

## 根因分析
`CallLogViewModel` 构造函数里，`repository = CallLogRepository.getInstance(application)` 原本被放在 `ThreadUtils.runOnUiThreadDelayed(...)` 的延迟任务内部（与蓝牙状态观察注册一起延迟执行）。构造完成后到延迟任务执行前的窗口期内，`repository` 为 null；未授权场景下重新进入页面会立即触发依赖仓库数据的路径（如 `repository.getBluetoothConnectionState().getValue()` 等成员方法），空指针导致闪退。缺陷库归因"数据对象延迟初始化导致空指针"，与代码完全吻合：核心数据成员的初始化被无必要地推迟到 UI 延迟队列中。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/CallLogViewModel.java`
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/viewmodel/CallLogViewModel.java
     public CallLogViewModel(Application application) {
         super(application);
+        repository = CallLogRepository.getInstance(application);
         ThreadUtils.runOnUiThreadDelayed(() -> {
-            // 监听蓝牙状态变化
-            repository = CallLogRepository.getInstance(application);
-
             // 观察蓝牙连接状态的变化
             LiveData<BluetoothConnectionState> bluetoothStateSource =
                     repository.getBluetoothConnectionState();
```

## 为什么能修复
`CallLogRepository.getInstance()` 是单例获取，构造期同步初始化成本极低，把它从延迟任务提到构造函数首行后，`repository` 自构造起非空，任何早于延迟任务的访问路径都不再 NPE；延迟任务内保留的 LiveData 观察注册引用的也是同一个已就绪的单例。副作用：单例会在 ViewModel 构造时创建（提前拿到 Context），因 `getInstance(application)` 本就为单例设计，无实际风险。

## 复盘与经验
- "初始化挪进延迟任务"只有在其中所有成员都无人提前访问时才安全；把纯创建型、无 UI 依赖的单例获取留在构造函数，只把观察注册等需要视图就绪的工作延迟，是更稳的分层。
- 退出重进类快速操作路径是延迟初始化类 bug 的天然触发器，测试用例应覆盖"杀进程后立即重进"。
- 空指针高发位可用 `Objects.requireNonNull`/late-init 断言让问题在开发期暴露，而不是线上闪退。

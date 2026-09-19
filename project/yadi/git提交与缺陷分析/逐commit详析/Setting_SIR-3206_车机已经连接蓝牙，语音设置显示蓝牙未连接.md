# SIR-3206 · 车机已连蓝牙但语音设置页显示未连接（日夜模式切换后 Fragment 生命周期错序）

- **提交**：`afac1acf` | 2026-07-21 | dufan | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 语音

## 问题
车机蓝牙实际已连接，进入设置里的语音设置页却显示"蓝牙未连接"；切换白天/黑夜模式后必现。

## 根因分析
`MainActivity.kt`（Setting）在 `initView()` 中执行 `initAdapter()`、`setPage(0)` 并用 `VoiceOperationUtil.handleIntent` 处理语音跳转。日/夜模式切换会重建 Activity：FragmentManager 先按 `savedInstanceState` 恢复旧的 Fragment 实例，随后 `initView` 又按自己的流程初始化页面。缺陷库记录："切换白天黑夜模式后，界面生命周期加载提前"——恢复出来的旧 Fragment 提前走了加载/展示逻辑，用旧的蓝牙状态渲染了"未连接"，而新流程后续的刷新被跳过或落在旧实例上，界面与真实蓝牙状态脱节。提交的修法（"判断是否移除 fragment"）正对应这一机制：重建时先把 FM 恢复的残留 Fragment 全部移除，再走全新初始化。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
+    private var mIsReCreate = false
+
+    override fun onCreate(savedInstanceState: Bundle?) {
+        mIsReCreate = savedInstanceState != null
+        super.onCreate(savedInstanceState)
+    }
+
     override fun initView() {
         LogUtils.i(Constants.Yadea_Trace, "Setting MainActivity_start")
         LogUtils.i(TAG, "V_vSet MainActivity init s start")
+        supportFragmentManager.fragments.forEach {
+            supportFragmentManager.beginTransaction().remove(it).commitNow()
+        }
         initAdapter()
         setPage(0)
         VoiceOperationUtil.handleIntent(intent, navItemList) { position -> setPage(position) }
```

## 为什么能修复
`onCreate` 里用 `savedInstanceState != null` 标记这是一次重建，`initView` 开头用 `remove(it).commitNow()` 同步移除 FragmentManager 恢复出的所有旧 Fragment，再 `setPage(0)` 重新添加——后续加载全部发生在全新实例上，蓝牙连接状态按当前真实值重新查询/渲染，不再被旧实例提前加载的"未连接"残留占据。`commitNow()` 保证移除立即生效，避免与 `setPage` 的 `add` 在同一帧内产生事务顺序问题。代价是放弃了 Fragment 状态恢复（重建即重新初始化），对设置页这类可完全重建的页面是合理取舍；`mIsReCreate` 字段在本 diff 中只赋值未消费，预留给后续逻辑。

## 复盘与经验
- 处理 uiMode/dark mode 切换时，Activity 重建 + FragmentManager 自动恢复是"生命周期加载提前"类 bug 的高发区；`initView` 开头清一次 FM 残留是最直接的防御。
- `remove().commitNow()` 与 `commit()` 的区别要清楚：这里需要同步生效，用 `commitNow` 避免恢复/新增事务交叠。
- "界面显示的状态与真实系统状态不符"且与配置切换相关时，先确认渲染数据来自新实例还是恢复的旧实例。

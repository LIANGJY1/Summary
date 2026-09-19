# 无单号 · Setting 黑白模式适配（哨兵模式入口 + 蓝牙图标语义色）

- **提交**：`51192409` | 2026-06-29 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
Setting 黑白模式适配的一个切片：新增"哨兵模式"说明弹窗（SentinelDialog）及入口点击，蓝牙/耳机/头盔/加载等约 30 个 drawable 改语义色，并删除误提交的夜间 HUD png。

## 实现结构
- `SystemFragment.kt`：给 `ivSentinel` 挂 `setOnFastClickListener`，弹出 `SentinelDialog`。
- `SentinelDialog.kt`（新增，25 行）：继承 `BaseDialogFragment`，仅 `DialogSentinelBinding.inflate` 返回根布局的纯展示弹窗。
- `SoundViewModel.kt`：删掉循环里一次冗余的 `ivLeft.setImageResource(R.drawable.ic_sound_left)`（下一行马上会按 index 覆盖）。
- 资源：`ic_bluetooth_switch/ic_choose_blue_transparent/ic_headphones*/ic_helmet*` 等扩写为多 path 语义色 vector；删除 `drawable-night/hud_light_high/low.png`（后被 vector 化替代）；`grabber/ic_connect_*` 等替换色引用。

数据流：设置页点击哨兵入口 → childFragmentManager 展示静态说明弹窗；其余为资源层变化，无状态流改动。

## 关键代码
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/SentinelDialog.kt（新增）
+class SentinelDialog() : BaseDialogFragment() {
+
+    private val mBinding by lazy {
+        DialogSentinelBinding.inflate(LayoutInflater.from(context), null, false)
+    }
+
+    override fun onCreateView(
+        inflater: LayoutInflater,
+        container: ViewGroup?,
+        savedInstanceState: Bundle?
+    ) = mBinding.root
+}
```
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
+        mBinding.ivSentinel.setOnFastClickListener {
+            SentinelDialog().show(childFragmentManager, "SentinelDialog")
+        }
```
```diff
# application/Setting/src/main/java/com/yadea/setting/ui/viewmodel/SoundViewModel.kt
             val rootView = dataBinding.root.findViewById<View>(it)
             val ivLeft = rootView.findViewById<ImageView>(R.id.iv_left)
-            ivLeft.setImageResource(R.drawable.ic_sound_left)
             val seekBar = rootView.findViewById<SeekBar>(R.id.seekbar_central)
```

实现讲解：纯展示弹窗用 `BaseDialogFragment + ViewBinding.inflate` 三步即可，不引入 ViewModel/回调，保持最小复杂度。SoundViewModel 的删除属于适配途中的顺手清理——被下一行无条件覆盖的 setImageResource 是无效 IO/查找，删掉不影响行为。

## 复盘与要点
- 可复用手法：功能性 UI（新入口+弹窗）可以寄生在主题适配提交里一起走，但更好的做法是分开提交，否则"黑白模式适配"标题会掩盖新功能入口的测试需求（影响等级却只标 D）。
- 哨兵弹窗 `DialogSentinelBinding` 复用了 `dialog_dial_style` 同期的布局产物，注意本次 diff 中同时出现了两个 Binding import，说明布局文件在别的提交里已备好，跨提交依赖需在合入顺序上保证。
- 遗留风险：SentinelDialog 无 onDismiss/确认回调，如后续要接"开启哨兵"动作需改造。

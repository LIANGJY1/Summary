# SIR-2782 · 控制中心点击蓝牙/WIFI/热点改为直接弹窗（Setting 侧）
- **提交**：`7d78eecc` | 2026-07-16 | dufan | Setting | bugfix（需求变更落地）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模（与 9e8db0a7 同单）

## 问题
与 `9e8db0a7` 同单的设置侧配套改造：`ConnectChildDialogActivity` 原本只会弹蓝牙弹窗，需要按 `type` extra 支持 wifi/hotspot/蓝牙三种弹窗，并处理 Activity 复用时的切换。

## 根因分析
SystemUI 侧改为直达 `ConnectChildDialogActivity` 后，该 Activity 原实现只在 `onCreate` 里无差别 `BluetoothDialogFragment`：1) 不解析 `type`，wifi/hotspot 也会弹蓝牙框；2) 逻辑写在 `onCreate`，Activity 因 `FLAG_ACTIVITY_NEW_TASK` 被复用时走 `onNewIntent`，新 type 不会生效；3) 三种弹窗互斥关系（弹 wifi 前应关掉蓝牙/热点框）没有处理。缺陷库"需求变更→只弹窗显示"，本提交即承载弹窗路由逻辑。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/activity/ConnectChildDialogActivity.kt（+68/-11）
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/activity/ConnectChildDialogActivity.kt
     override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)
-        if (mBluetoothDialogFragment == null || !mBluetoothDialogFragment!!.isVisible) { ... }
+        showDialog(intent)
     }
     override fun onNewIntent(intent: Intent?) {
         super.onNewIntent(intent)
+        showDialog(intent)
+    }
+    private fun showDialog(intent: Intent?) {
+        intent?.apply { SIsNeedFinish = ...; SIsFromCarConnect = ... }
+        when (intent?.getStringExtra("type")) {
+            "wifi" -> {
+                mBluetoothDialogFragment?.safeDismiss()
+                mHotspotDialogFragment?.safeDismiss()
+                if (mWlanDialogFragment == null || !mWlanDialogFragment!!.isVisible) {
+                    mWlanDialogFragment = WlanDialogFragment()
+                    mWlanDialogFragment?.setOnDismissListener { finish() }
+                    mWlanDialogFragment?.show(supportFragmentManager, "WlanDialogFragment")
+                } ...
+            }
+            "hotspot" -> { /* 同构：关另两个，show HotspotDialogFragment */ }
+            else   -> { /* 默认：BluetoothDialogFragment */ }
+        }
     }
```

## 为什么能修复
弹窗选择抽成 `showDialog(intent)` 且 onCreate/onNewIntent 双入口共用：冷启动与复用两条路径都按 type 路由；每个分支先 `safeDismiss()` 另两个弹窗保证互斥，dismiss 时 `finish()` 收尾。可注意到两处瑕疵：`else` 分支里 `(findFragmentByTag(...) as? BaseDialogFragment)?.showsDialog` 是只读表达式，无任何效果（疑似想写 re-show，未生效）；成员变量缓存 Fragment 与 FragmentManager 的 tag 查找并存，旋转屏幕等重建场景下成员引用会失效，依赖 `isVisible` 判断可能误判。

## 复盘与经验
- **Activity 复用必须处理 onNewIntent**：`NEW_TASK + singleTask` 类入口 Activity，所有基于 Intent 的行为都应同时挂在 onCreate 与 onNewIntent 上，统一抽一个 handler。
- **互斥 UI 要"先关旧再开新"**：弹窗组切换时逐个显式 dismiss，防止叠层与状态互踩。
- **警惕无副作用的 Kotlin 表达式**：`?.showsDialog` 读属性不成语句，编译器不报错但逻辑缺失，code review 靠语义读才能发现。

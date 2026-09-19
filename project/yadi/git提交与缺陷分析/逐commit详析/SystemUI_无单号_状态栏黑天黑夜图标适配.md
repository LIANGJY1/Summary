# [SIR-XXXX] · SystemUI 状态栏黑天黑夜图标适配

- **提交**：`1980691a` | 2026-06-29 | liujinfeng | SystemUI | feature
- **关联单**：SIR-XXXX 占位号

## 需求/目标
状态栏全套图标从 mdpi 位图迁移为语义色 vector，使信号格数、网络类型（1x/2G/3G/4G/5G）、通知红点、DVR 录像等图标在白天/黑夜模式下自动反色。

## 实现结构
- `statusbar/icon/PhoneStatusBarPolicy.java`：`resolveNetworkTypeLabel()` 中 5G/4G/3G/2G/1x 共 10 处 `R.drawable.icon_*` 全部替换为 `R.drawable.vector_signal_*`。
- `statusbar/ui/StatusBarFragment.java`：`initStatusIcons()`、`createSignalContainer()`、`showNoSignal()`、`updateSignalIcon(level)`（信号强度数组 0-5）、`resolveNotificationIconResId()`（红点/灰点/正常三态）、DVR 状态图标共 15+ 处引用同步替换为 `vector_signal_*`、`vector_notify_*`、`vector_dvr_*`。
- 资源：删除 `drawable-mdpi/` 下 19 张 png（icon_1x~5g、icon_signal_0~5、icon_dvr_*、icon_notification_* 等）；新增/替换 `vector_arrow_up.xml`、`vector_dvr_error/normal.xml` 等 vector，fillColor 均引用语义色。

数据流：信号/通知/DVR 状态回调 → 上述 resolve/update 方法返回 vector 资源 id → ImageView 设置；uiMode 切换时 vector 的语义色由资源系统自动取反色，无需重建状态栏。

## 关键代码
```diff
# application/SystemUI/src/main/java/com/android/systemui/statusbar/ui/StatusBarFragment.java
     public void updateSignalIcon(int level) {
         if (mIvSignal == null) return;
         int[] signalIcons = {
-            R.drawable.icon_signal_0,
-            R.drawable.icon_signal_1,
-            R.drawable.icon_signal_2,
-            R.drawable.icon_signal_3,
-            R.drawable.icon_signal_4,
+            R.drawable.vector_signal_1,
+            R.drawable.vector_signal_2,
+            R.drawable.vector_signal_3,
+            R.drawable.vector_signal_4,
+            R.drawable.vector_signal_5,
         };
```
```diff
# application/SystemUI/src/main/java/com/android/systemui/statusbar/icon/PhoneStatusBarPolicy.java
         if (overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                 || overrideType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE) {
-            return R.drawable.icon_5g;
+            return R.drawable.vector_signal_5g;
         }
```

实现讲解：适配手法很克制——不改任何状态机逻辑，只做资源 id 替换，把"日夜反色"完全下沉到 vector 的 `fillColor=@color/...`；位图换 vector 还顺带解决了多 dpi 维护和包体积问题（19 张 png 一次清空）。

## 复盘与要点
- 可复用手法：状态栏/常驻图标这类"多状态小图标"应一开始就用语义色 vector + `resolveXxxResId()` 集中返回，日夜适配即退化为纯资源工作。
- 注意本提交把 `vector_signal_0` 改名为 `vector_signal_1` 起（数组下标语义从"0 格"变为"1 格"），`idx = level` 的边界逻辑依赖调用方 level 从 0 起，替换时需同步核对语义，否则信号格会错一格。
- SystemUI 侧改动极小（两个 java 文件 44 行），但 commit 携带大量 build 产物（与 54d746cf 同一问题），仓库瘦身需另立专项。

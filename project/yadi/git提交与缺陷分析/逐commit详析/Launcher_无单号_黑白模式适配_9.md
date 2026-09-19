# 无单号 · Launcher 黑白模式适配（互联页 onConfigurationChanged 手动刷肤 + LinkActivity 补资源）

- **提交**：`b58a35fc` | 2026-06-30 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
Launcher 互联相关页面的黑白模式适配：蓝牙配对页（LinkActivity）、互联列表页（CarConnectFragment）、应用详情列表（AppDetailListAdapter）在 uiMode 切换时手动重设背景与文字色；新增蓝牙 vector 图标与加载动画资源；顺带移除布局里的旧换肤 tag。

## 实现结构
- `CarConnectFragment.kt` / `LinkActivity.kt`：新增 `onConfigurationChanged(newConfig)` 重写，逐一 `setBackgroundResource` + `setTextColor(ResourceUtils.getColor(语义色))` 重刷卡片（carplay/hicar/carlink 三卡片、PIN 码 6 位、蓝牙标题/提示）。
- `AppDetailListAdapter.kt`：应用名颜色从按名查找 `getColor("color_item_app_name")` 改为 `getColor(R.color.text_default_default)`；清理 9 个无用 import。
- 两处新增私有扩展 `setBackgroundResource(view, resId)`：先置空 background 再设置，规避 selector 状态残留。
- 资源：新增 `ic_bluetooth.xml`（68x79 蓝牙 vector，语义色）；`ic_loading.xml` 重制（40 行）；`activity_link.xml` 加蓝牙视图、`fragment_carconnect_list.xml` 文字色语义化并删除 `android:tag="skin:home_bg:background"` 换肤标记；`AndroidManifest.xml` activity 声明同步微调。

数据流：系统 uiMode 变化 →（Launcher 未声明 uiMode 的 configChanges，见 31f1f23c 的差异）Activity/Fragment 走 onConfigurationChanged → 手动重设资源 → 视图恢复正确配色。

## 关键代码
```diff
# application/Launcher/src/main/java/com/yadea/launcher/function/applist/CarConnectFragment.kt
+    override fun onConfigurationChanged(newConfig: Configuration) {
+        super.onConfigurationChanged(newConfig)
+        setBackgroundResource(mBinding.layoutCarplay, R.drawable.bg_car_card_selector)
+        mBinding.tvCarplayName.setTextColor(ResourceUtils.getColor(R.color.text_default_default))
+        mBinding.tvCarplayStatus.setTextColor(ResourceUtils.getColor(R.color.text_default_press))
+        ... // hicar / carlink 卡片同理
+    }
+
+    private fun setBackgroundResource(view: View, resId: Int) {
+        view.background = null
+        view.setBackgroundResource(resId)
+    }
```
```diff
# application/Launcher/src/main/java/com/yadea/launcher/adapter/AppDetailListAdapter.kt
-            .setTextColor(R.id.tv_app_name, getColor("color_item_app_name"))
+            .setTextColor(R.id.tv_app_name, getColor(R.color.text_default_default))
```

实现讲解：这段代码揭示了本项目的适配双轨制——常规资源靠 `values-night` 自动切换，但 RecyclerView 已绑定视图、Dialog 型 Activity 等不会自动重建的场景，需要在 `onConfigurationChanged` 里手动重刷一遍。`background = null` 再 set 是防止同一个 selector drawable 实例被复用后状态错乱的防御写法。

## 复盘与要点
- 可复用手法：`onConfigurationChanged` 集中重刷的"刷肤清单"模式；更进一步可用 `ResourceUtils.applySkin(rootView)` 之类的工具统一递归处理，避免逐控件罗列（本提交罗列了 15+ 行，新增控件极易漏）。
- 删除 `tag="skin:home_bg:background"` 说明项目正从旧换肤方案（ChangeSkinManager，见 7366e05d）迁移到 uiMode 资源方案，两套并存期的清理要跟上。
- 遗留风险：`getColor(R.color.x)` 若资源只有 values 版没有 night 版，夜间会显示白天色——依赖 CommonUI 语义色先就位，跨模块合入顺序敏感。

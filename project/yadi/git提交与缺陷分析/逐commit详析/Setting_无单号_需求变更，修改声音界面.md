# 无单号 · 需求变更，修改声音界面

- **提交**：`2dc45bd7` | 2026-08-07 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
声音界面按新 UI 重构：声音拆分为"音量/音效"两级容器，并顺手把 fb2398df 中复制出来的容器逻辑抽象为公共 `BaseContainerFragment`，新增安全监控页的 ViewModel 数据层。

## 实现结构
15 个文件、+1500/-450 量级。核心三块：
- 新增 `base/BaseContainerFragment.kt`（83 行）：抽象出 `getTabTitles()/getFragments()` 模板方法，统一 ViewPager2 + TabLayout + 固定指示器装配，并把 fb2398df 里 ControlFragment/DrivingContainerFragment 的重复代码收敛（两者各删 70+ 行）；
- 声音重构：新增 `SoundContainerFragment`（音量/音效两 Tab）、`VolumeFragment`（441 行，各类音量条）、`SoundEffectFragment`（102 行）、配套 `VolumeViewModel`（254 行）；
- 安全监控补数据层：新增 `SafetyMonitorViewModel`（155 行），`SafetyMonitorFragment` +163 行接线。
数据流维持 SettingVehicleService 信号 → LiveData → Fragment/ViewModel 的既有架构。

## 关键代码
```diff
--- /dev/null  (application/Setting/src/main/java/com/yadea/setting/base/BaseContainerFragment.kt)
+abstract class BaseContainerFragment : BaseFragment<FragmentControlBindingImpl, BaseViewModel>() {
+
+    abstract fun getTabTitles(): List<String>
+
+    abstract fun getFragments(): List<BaseFragment<out ViewDataBinding, out BaseViewModel>>
+
+    private fun setupViewPager() {
+        val tabTitles = getTabTitles()
+        val fragments = getFragments()
+        pagerAdapter = DrivingPagerAdapter(childFragmentManager, lifecycle)
+        ...
+        for (i in 0 until mBinding.tabLayout.tabCount) {
+            mBinding.tabLayout.getTabAt(i)?.view?.let { tabView ->
+                ViewCompat.setTooltipText(tabView, null)
+                tabView.isLongClickable = false
+                tabView.setOnLongClickListener { true }
+            }
+        }
+    }
```
实现讲解：把"容器页"沉淀为模板方法基类后，新增一个多 Tab 页面只需声明标题与子 Fragment 列表；还顺带修了 Tab 长按弹 tooltip 的车机交互问题（禁 longClickable）。VolumeViewModel 的出现说明重构同时把音量页从"Fragment 直连信号"补齐为 MVVM 分层。

## 复盘与要点
- "先复制落地、需求稳定后立刻抽基类"的重构节奏在本次得到验证：fb2398df（复制）→ 2dc45bd7（抽象）相隔 5 小时。
- BaseContainerFragment 绑定 `FragmentControlBindingImpl` 复用控件布局，代价是所有容器页共享同一布局 id（fragment_control），语义与文件名耦合，后续页面命名会误导。
- 单提交混合"抽象重构 + 声音新功能 + 安全监控数据层"三件事，回滚粒度偏粗。

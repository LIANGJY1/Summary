# 无单号 · 需求变更：车控控制、驾驶界面重构

- **提交**：`fb2398df` | 2026-08-07 | sgh | Setting | feature（大型界面重构首期）
- **关联单**：无（Change-Id I164a540e，与 7e985e76/8f59a234/91d9d21a 同一需求链）

## 需求/目标
按最新 UI 需求重构设置应用的"车控"与"驾驶"两个一级页面：原来平铺的 驾驶/车控/灯光/行车辅助/场景模式 五个入口，收敛为"控制"与"驾驶"两个 Tab 容器页，子功能下沉为页内 ViewPager2 标签。

## 实现结构
约 27 个文件、+1300/-800 量级。结构变化：
- 新增两个容器 Fragment：`ControlFragment`（4 子 Tab：座椅&手把、灯光、安全监控、模式）与 `DrivingContainerFragment`（3 子 Tab：驾驶操控、行车辅助、主动安全），配套 `ControlPagerAdapter`/`DrivingPagerAdapter`（FragmentStateAdapter）与新布局 `fragment_control.xml`/`fragment_driving_container.xml`；
- 新增 `DrivingSafetyFragment`（344 行，主动安全页，含碰撞预警等设置）、重写 `AssistedDrivingFragment`（223 行改动）、`SceneModeFragment`（388 行改动，场景模式由弹窗 `SceneModeDialogFragment` 改为独立页，删除旧 dialog 布局）；
- `MainActivity` 导航列表由 DrivingFragment/VehicleControlFragment/LightFragment/AssistedDrivingFragment/SceneModeFragment 五项替换为 ControlFragment + DrivingContainerFragment 两项；
- `SettingVehicleService` 同步信号接线：新增转向补盲影像、迎宾灯光/音效开关，注释掉迎宾模式总开关、低电量模式等。
数据流不变：SettingVehicleService 信号映射 → MutableLiveData → 各 Fragment observe 渲染 / sendVehicleProperty 下发。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/ControlFragment.kt
+    private val fragments = listOf(
+        VehicleControlFragment(),
+        LightFragment(),
+        SafetyMonitorFragment(),
+        SceneModeFragment()
+    )
+    private fun setupViewPager() {
+        pagerAdapter = ControlPagerAdapter(childFragmentManager, lifecycle)
+        pagerAdapter?.setFragments(fragments)
+        mBinding.viewPager.setUserInputEnabled(false) // 禁止手指滑动
+        mBinding.viewPager.adapter = pagerAdapter
+        // 预加载所有页面，避免切换时重新创建
+        mBinding.viewPager.offscreenPageLimit = fragments.size
```
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
         val fragmentList = listOf(
-            DrivingFragment::class.java,
-            VehicleControlFragment::class.java,
-            LightFragment::class.java,
-            AssistedDrivingFragment::class.java,
-            SceneModeFragment::class.java,
+            ControlFragment::class.java,
+            DrivingContainerFragment::class.java,
```
实现讲解：重构采取"容器页 + 子 Fragment"的两级导航模式，一级导航从 5 个减到 2 个但功能不减——所有旧 Fragment 原样搬进容器，重活只在导航层；ViewPager2 禁手势、offscreenPageLimit 全量预加载，子页保留各自 ViewModel。指示器用 LayerDrawable 固定 60x3dp 居中实现设计稿样式。

## 复盘与要点
- "只动导航层、子页平移"是界面重构低风险路径，旧 Fragment 的信号逻辑零改动，回归面可控。
- ControlFragment 与 DrivingContainerFragment 代码几乎复制（setupViewPager/指示器完全相同），三天后 `2dc45bd7` 就补抽了 `BaseContainerFragment`——先复制后抽象的节奏，可见重构初期的迭代压力。
- 全量预加载 4 个子页在车机低端屏上或引发首屏卡顿，可评估懒加载。

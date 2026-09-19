# 无单号 · [界面重构] 车控界面重构修改

- **提交**：`7e985e76` | 2026-08-10 | sgh | Setting | feature（界面重构第二期）
- **关联单**：无（Change-Id I164a540e，重构需求链）

## 需求/目标
车控界面重构二期：把 HUD（抬头显示）从 VehicleControlFragment 拆出为独立 `HudFragment`，显示设置改为容器结构（Dashboard/HUD），VehicleControlFragment 从 788 行减重到只剩座椅&把手职责，SystemFragment 减重 219 行。

## 实现结构
15 个文件、+980/-1367（净减 387 行）。主要动作：
- 新增 `HudFragment`（620 行）：HUD 开关、亮度（1~10 档默认 5）、显示模式等全部 HUD 设置迁入，配套 `fragment_hud.xml`；
- 新增 `DisplayContainerFragment`（24 行，继承后建的 BaseContainerFragment 模式）与 `DashboardFragment`（72 行），MainActivity 导航里 DisplayFragment 替换为 DisplayContainerFragment；
- `VehicleControlFragment` -700+ 行（HUD 相关全部迁出），`SystemFragment` -219 行（仪表相关迁往 Dashboard）；
- 布局 `fragment_vehicle_control.xml`/`fragment_system.xml`/`fragment_display.xml` 对应瘦身。
数据流不变：SettingVehicleService LiveData ↔ 各 Fragment。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/activity/MainActivity.kt
             ControlFragment::class.java,
             DrivingContainerFragment::class.java,
             SoundContainerFragment::class.java,
-            DisplayFragment::class.java,
+            DisplayContainerFragment::class.java,
             ConnectFragment::class.java,
```
```diff
--- /dev/null  (application/Setting/src/main/java/com/yadea/setting/ui/fragment/HudFragment.kt)
+/**
+ * HUD界面
+ */
+class HudFragment : BaseFragment<FragmentHudBinding, BaseViewModel>() {
+
+    //HUD开关临时值
+    private var hudSwitchStateTemp: Boolean? = null
+
+    /**
+     * HUD亮度设置：可设置1~10档位，默认为5档；
+     */
+    private var hudBrightnessState = 10
```
实现讲解：二期重点是"职责拆分"——原 VehicleControlFragment 承担车控+HUD+仪表过多职责，按设置分组拆为 HudFragment、DashboardFragment 后单文件回归设计稿体积；HUD 设置迁出时连 switch 的防回弹临时值（hudSwitchStateTemp）模式一并搬运，保证交互行为不变。

## 复盘与要点
- 净减近 400 行的"减法提交"在功能开发分支里少见，说明一期容器化后二期进入拆分收敛阶段，重构有明确规划。
- HUD 状态防回弹（temp 值 + SwitchHelper.cancelRebound）在多个 Fragment 重复出现，可下沉公共扩展函数。
- 拆分后 VehicleControlFragment/HudFragment/DashboardFragment 仍共享同一 SettingVehicleService 信号源，职责边界在数据层尚未对应拆分。

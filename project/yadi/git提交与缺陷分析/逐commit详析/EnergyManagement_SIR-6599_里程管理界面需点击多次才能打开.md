# SIR-6599 · 能量中心里程管理卡片需点击多次才能打开

- **提交**：`bd9ddf5e` | 2026-08-26 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 主交互

## 问题
能量中心主界面点击"里程管理"卡片时，点击文字或空白区域无反应，必须多次尝试、点中图标热区才能进入里程管理界面。

## 根因分析
`EnergyManagement` 的 `MainActivity` 中，跳转 `MileageManagementActivity` 的点击事件只绑定在图标 `iv_mileage_management_icon` 上（`onClick` 里也只判 `v.getId() == R.id.iv_mileage_management_icon`）；承载卡片的 `LinearLayout`（`ll_module_mileage`，背景 `bg_bottom_card_mileage`）没有绑定监听，也没有 `clickable/focusable` 属性。卡片上的文字、空白区域点击后事件无人消费，视觉上"点了卡片没反应"。用户以为整个卡片可点，实际有效热区只有小图标，于是表现为"偶现点不开/要点多次"——本质是热区远小于用户心理预期区域（缺陷库根因：卡片文字及空白区域未设置点击监听）。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`、`application/EnergyManagement/src/main/res/layout/activity_main.xml`

```diff
--- a/application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ -221,6 +221,8 @@ public class MainActivity extends BaseActivity implements View.OnClickListener,
         // 设置里程管理点击事件
+        // 绑定整个卡片，避免用户点击图标以外的区域时触摸事件无法触发跳转。
+        binding.llModuleMileage.setOnClickListener(this);
         binding.ivMileageManagementIcon.setOnClickListener(this);
@@ -368,6 +370,7 @@
         binding.ivSlowChargeInfo.setOnClickListener(null);
         binding.maxChargeLimitInfo.setOnClickListener(null);
+        binding.llModuleMileage.setOnClickListener(null);
         binding.ivMileageManagementIcon.setOnClickListener(null);
@@ -1799,7 +1802,7 @@ public class MainActivity extends BaseActivity implements View.OnClickListener,
     @Override
     public void onClick(View v) {
         // 里程管理点击事件 - 跳转到新的Activity
-        if (v.getId() == R.id.iv_mileage_management_icon) {
+        if (v.getId() == R.id.ll_module_mileage || v.getId() == R.id.iv_mileage_management_icon) {
             LogUtils.d(TAG, "[MileageDebug] mileage entry clicked");
             Intent intent = new Intent(this, MileageManagementActivity.class);
             startActivity(intent);
```

```diff
--- a/application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ -695,6 +695,8 @@
                 android:layout_height="120dp"
                 android:layout_marginStart="10dp"
                 android:background="@drawable/bg_bottom_card_mileage"
+                android:clickable="true"
+                android:focusable="true"
                 android:gravity="center_horizontal|bottom"
```

## 为什么能修复
布局上给 `ll_module_mileage` 加 `clickable=true` + `focusable=true`，使其成为可点击目标；代码里把同一 `OnClickListener` 绑到整个卡片，`onClick` 判定加入 `ll_module_mileage` 分支，与图标共用跳转逻辑。有效点击区域从小图标扩大到整张 120dp 卡片，任意位置单击即跳转，"多次点击"现象消除。细节规范：清理路径（`setOnClickListener(null)`）同步解绑卡片监听，避免复用/刷新时泄漏回调。隐患：卡片内部若有后续新增的子控件需要独立点击，要注意子控件优先消费事件、避免整卡点击造成误跳转。

## 复盘与经验
- 可点击区域的 UX 原则：用户认为"一张卡片"可点，热区就必须是整卡；只绑图标是"实现视角"而不是"用户视角"，测试报"偶现"的点击问题先查热区覆盖。
- 在 XML 给容器补 `clickable/focusable` 的同时必须在代码绑定监听，二者缺一不可：只 clickable 无监听点了没反应，只绑监听不 clickable 在部分场景同样不响应。
- 点击入口多处绑定时收敛到同一 Listener + id 分支（本例做法），保证多热区行为一致，避免日后改跳转逻辑漏改一处。

# SIR-7006 · 多次点击里程管理按钮弹出多个里程设置界面
- **提交**：`9c6243e4` | 2026-09-01 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
在能量中心主页面快速多次点击"里程管理"入口，会弹出多个里程设置界面（MileageManagementActivity 被重复创建压栈）。

## 根因分析
`MainActivity.onClick()` 中对 `ll_module_mileage` / `iv_mileage_management_icon` 的处理是直接 `startActivity(new Intent(this, MileageManagementActivity.class))`，没有任何防重入保护。快速连点时第一次点击尚未使 MainActivity 进入 onPause，后续点击仍会继续触发 startActivity；同时 Manifest 中 `MileageManagementActivity` 未配置 launchMode（默认 standard），每次启动都在栈顶新建实例，导致界面对象叠加。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/AndroidManifest.xml`、`application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java`
```diff
--- application/EnergyManagement/src/main/AndroidManifest.xml
         <activity
             android:name="com.android.yadea.energymanagement.view.ui.MileageManagementActivity"
+            android:launchMode="singleTop"
             android:theme="@style/MainTheme" />

--- application/EnergyManagement/src/main/java/com/android/yadea/energymanagement/view/ui/MainActivity.java
@@ onClick(View v)
         if (v.getId() == R.id.ll_module_mileage || v.getId() == R.id.iv_mileage_management_icon) {
+            if (mMileagePageLaunching) {
+                LogUtils.d(TAG, "[MileageDebug] duplicate mileage entry click ignored");
+                return;
+            }
+            mMileagePageLaunching = true;
             LogUtils.d(TAG, "[MileageDebug] mileage entry clicked");
             Intent intent = new Intent(this, MileageManagementActivity.class);
             startActivity(intent);
@@ onResume()
+        mMileagePageLaunching = false;
```

## 为什么能修复
双保险消除根因：其一，`mMileagePageLaunching` 跳转锁在点击后立即置 true，连续点击被直接忽略；锁在 MainActivity 的 `onResume()` 中复位，用户从里程页返回主页时自动解锁，不会出现"点一次后永远点不动"的副作用。其二，`singleTop` 使目标页即使被再次启动也复用栈顶实例，不再叠加。

## 复盘与经验
- 防抖 + launchMode 应成对使用：单靠启动锁，动画窗口外的极端时序仍可能重复入栈；单靠 singleTop，若后续页面改成带参数刷新语义则可能掩盖问题。
- 锁的解锁时机要绑定"回到发起方"的生命周期（onResume），而不是定时器，避免固定延时在低端机上不可靠。
- 车机 HMI 上按钮无 hover 反馈，用户更倾向连点，所有"跳转类"点击都应默认考虑防重入。

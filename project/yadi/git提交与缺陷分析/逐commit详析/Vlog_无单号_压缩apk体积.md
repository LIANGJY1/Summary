# 无单号 · Vlog 模块压缩 APK 体积（裁剪拍摄/抓拍功能）

- **提交**：`1e1a4aed` | 2026-07-28 | daizhecheng | Vlog | feature
- **关联单**：无

## 需求/目标
性能需求：删除 Vlog 模块中未被正式启用的 Camera2 拍照与 Capture 抓拍整条功能链，直接以源码裁剪的方式压缩 APK 体积。

## 实现结构
- `application/Vlog/src/main/AndroidManifest.xml`：移除 `Camera2Activity`、`CaptureActivity` 两个 Activity 声明
- 删除 camera 包（Camera2Activity/CameraFragment/CameraSizes/ExifUtils/OrientationLiveData 等）
- 删除 capture 包（CaptureActivity/CaptureConst/CaptureEvent/CaptureViewModel 880 行等）
- 删除 insta 包相机管理扩展（InstaCameraManagerExt 306 行、InstaMediaListener）
- 删除自定义 View（CaptureShutterButton/FadingEdgeDecoration/transform 变换器）与对应布局 activity_capture.xml、activity_camera2.xml
- `CameraPairedActivity.kt`：移除"打开抓拍"按钮入口 `btnOpenCapture` 的点击逻辑
- `SystemExt.kt`/`ConnectViewModel.kt`：清理无用 import、补充 BLE 引用

数据流：删除从入口（按钮→Activity 声明）到实现（ViewModel→相机 SDK 封装→自定义控件→布局）的整条链路，入口与实现同步摘除，保证编译通过且不留死代码。

## 关键代码
```diff
--- a/application/Vlog/src/main/AndroidManifest.xml
@@ -85,16 +85,6 @@
             android:configChanges="uiMode|screenLayout|screenSize"
             android:exported="true"
             android:theme="@style/MainTheme" />
-        <activity
-            android:name="com.yadea.vlog.camera.Camera2Activity"
-            android:configChanges="uiMode|screenLayout|screenSize"
-            android:exported="true"
-            android:theme="@style/MainTheme" />
-        <activity
-            android:name="com.yadea.vlog.capture.CaptureActivity"
-            android:configChanges="uiMode|screenLayout|screenSize"
-            android:exported="true"
-            android:theme="@style/MainTheme" />
```
```diff
--- a/application/Vlog/src/main/java/com/yadea/vlog/main/ui/CameraPairedActivity.kt
@@ -64,23 +59,12 @@
-        mBinding.btnOpenCapture.setOnFastClickListener {
-            LogUtils.i(TAG, "btnOpenCapture")
-            if (viewModel.isConnected) {
-                startActivity(
-                    Intent(
-                        this@CameraPairedActivity, CaptureActivity::class.java
-                    )
-                )
-            }
-        }
```
实现讲解：manifest 中先摘除 Activity 声明（防止残留入口导致启动崩溃），再同步删除点击跳转逻辑与全部实现文件，属于"入口+实现"一体化裁剪；stat 显示 27 个文件净删 3493 行、仅增 11 行，是典型的减法式瘦身提交。

## 复盘与要点
- 用"删源码"而非仅靠 ProGuard/R8 裁剪，能同时减掉资源布局与 manifest 元数据，瘦身效果更彻底，但要求删除前确认功能确无入口调用。
- 整链路删除（入口→页面→ViewModel→控件→布局）一次提交完成，避免了残根引发编译错误的风险，可作为功能下线 SOP。
- 遗留风险：相机配对页布局 `camera_pair_activity.xml` 中若仍保留 `btnOpenCapture` 控件而代码不再绑定，属死资源；后续可再清理布局与 drawable。

# SIR-5765 · 副蓝牙刷新按钮秒点秒停，无 Loading 动效

- **提交**：`659a9a79` | 2026-08-11 | daizhecheng | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设（rc：动画问题 / sol：修改动画逻辑）

## 问题
系统设置-副蓝牙连接界面，点击"刷新"按钮后旋转 Loading 动画瞬间出现又瞬间停止（秒点秒停），看不到扫描加载动效。

## 根因分析
三个层面叠加：
1. **语义错误**：`BluetoothAnwAdapter` 点击回调发送 `onScanningStateChanged(!isAnimating)`——把请求状态取反成"切换(toggle)"语义，且以**动画状态** `isAnimating` 为准而非**扫描业务状态** `mIsScanInProgress`。两个状态一旦不同步（如扫描已开始但动画因视图回收停了，或反之），点击发出的意图与业务状态相反：动画在转但业务态为"未扫描"时发出 false，把刚启动的扫描/动画立即停掉，即"秒点秒停"。
2. **去重条件不对称**：`BluetoothAnwFragment` 只在 `isScan && mIsScanInProgress` 时忽略点击，`isScan=false && mIsScanInProgress=false` 等其它组合会继续执行停止分支，放大误停。
3. **动画复位缺陷**：`RotatingImageViewHelper.startRotation()` 直接 `start()`，上一次 `end()` 后 `imageView` 残留任意角度，且 RecyclerView 复用绑定新 holder 时无人重启动画（`mRotationHelper` 只在首次 bind 创建），动画状态丢失后无法自恢复。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt`、`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt`、`application/Setting/src/main/java/com/yadea/setting/utils/RotatingImageViewHelper.java`
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/adapter/BluetoothAnwAdapter.kt
+        if (mIsRefreshing) {
+            mRotationHelper?.let { helper ->
+                if (!helper.isAnimating) {
+                    helper.setImgRes(R.drawable.ic_loading)
+                    helper.startRotation()
+                }
+            }
+        }
         holder.getView<View>(R.id.ll_refresh).setOnFastClickListener {
-            mScanningStateChangedListener?.onScanningStateChanged(!isAnimating)
+            mScanningStateChangedListener?.onScanningStateChanged(true)
         }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothAnwFragment.kt
-                if (isScan && mIsScanInProgress) {
+                if (isScan == mIsScanInProgress) {
                     log("$TAG-> scan click ignored: already scanning")
                     return
                 }
```
```diff
// application/Setting/src/main/java/com/yadea/setting/utils/RotatingImageViewHelper.java
         if (imageView == null || objectAnimator == null || isAnimating) {
             return;
         }
+        objectAnimator.cancel();
+        objectAnimator.setFloatValues(0, 360);
+        objectAnimator.setCurrentPlayTime(0);
+        imageView.setRotation(0);
         objectAnimator.start();
@@
         objectAnimator.cancel();
-        objectAnimator.end();
+        objectAnimator.setCurrentPlayTime(0);
+        imageView.setRotation(0);
         isAnimating = false;
```
另外 `setRefresh(isRefresh)` 把业务状态存入新增的 `mIsRefreshing` 字段，供 bind 时自恢复动画。

## 为什么能修复
刷新按钮固定发 `true`（"请求开始扫描"）后不再有误停意图；Fragment 用 `isScan == mIsScanInProgress` 对称判重，只有请求态与业务态相同才忽略，状态机语义干净；`mIsRefreshing` + bind 时自启动让 RecyclerView 复用/重绑后动画自动恢复；`RotatingImageViewHelper` 在 start/stop 时统一复位到 0 度并重设 `(0,360)` 关键帧，每次旋转都从 0 干净起步，消除残角与停不干净的问题。副作用：`isAnimating` 仍以 Helper 自身为准，若动画被系统取消（页面不可见暂停）而未走 stopRotation，`mIsRefreshing` 兜底重绑可部分弥补。

## 复盘与经验
- "点击=toggle"还是"点击=固定意图"必须先定语义；刷新按钮天然是"请求开始"，toggle 语义叠加双状态源必然产生反向操作。
- 动画状态（isAnimating）与业务状态（isScanning）是两个东西，UI 回调传递意图时只能引用业务状态；RecyclerView 场景动画还要处理视图复用后的自恢复。
- ValueAnimator 复用前要 `cancel()+resetPlayTime()+复位属性`，`end()` 残留的属性值是"第二次动画表现异常"的高频原因。
- 低概率复现的动画 bug，往往根因是状态不同步而非动画本身；修状态机，动画自然正常。

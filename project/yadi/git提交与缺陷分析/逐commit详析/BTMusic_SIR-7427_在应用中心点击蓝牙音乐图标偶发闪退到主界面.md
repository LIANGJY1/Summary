# SIR-7427 · 应用中心点击蓝牙音乐图标偶发闪退回主界面
- **提交**：`47cd60f1` | 2026-09-09 | dufan | BTMusic | bugfix
- **缺陷库**：等级 C · 频次 偶现-低于10% · 状态 待测试验证 · 域 本地多媒体（根因：界面复用时没有创建 surface）

## 问题
在应用中心点击蓝牙音乐图标，偶发应用闪退直接回到主界面（桌面）。

## 根因分析
`BTMusic` 的入口 Activity `MainActivity`（`launchMode="singleTask"`）在 Manifest 中声明了 `android:resumeWhilePausing="true"` 与 `android:stateNotNeeded="true"`。`resumeWhilePausing` 允许目标 Activity 在前一 Activity 尚未 pause 完成时提前 resume；`stateNotNeeded` 则允许其被重建/复用时不保存恢复状态。两者叠加改变了正常启动/复用时序，出现"界面复用时没有创建 surface"（缺陷库登记根因）——Activity 复用走旁路时 SurfaceView/渲染表面未就绪即恢复 UI，偶发崩溃退回桌面。修复方向是删除这两个非标准属性，让 `MainActivity` 走系统正常的复用生命周期（onPause→onResume 完整时序，surface 正常创建）。

## 关键代码修改
改动文件：application/BTMusic/src/main/AndroidManifest.xml
```diff
--- application/BTMusic/src/main/AndroidManifest.xml
@@ -126,8 +126,6 @@
             android:configChanges="screenLayout|screenSize|smallestScreenSize|orientation|uiMode|locale|layoutDirection|touchscreen"
             android:exported="true"
             android:launchMode="singleTask"
-            android:resumeWhilePausing="true"
-            android:stateNotNeeded="true"
             android:theme="@style/MainTheme"
             tools:ignore="Instantiatable">
```

## 为什么能修复
去掉 `resumeWhilePausing` 后，Activity 恢复不再与前一界面 pause 并发竞争，surface 创建在正常 onResume 时序内完成；去掉 `stateNotNeeded` 后系统按标准路径保存/恢复状态，复用时序回归常规，消除了 surface 未建即渲染的崩溃窗口。注意点：这两个属性通常是为了加快车机启动/切换速度而加的，删除后冷启动到可交互的时长可能略有回退，属于"稳定换性能"的取舍；缺陷当前状态"待测试验证"，偶现问题需长时间压测确认。

## 复盘与经验
- `resumeWhilePausing`/`stateNotNeeded` 属于非常规优化属性，会改变 Activity 生命周期时序，与 `singleTask` 复用、SurfaceView 渲染叠加时易产生偶现崩溃，没有明确性能数据支撑不要加。
- 偶现闪退排查从 Manifest 属性、launchMode、surface 生命周期三者的组合入手，往往比盯 Java 堆栈更直接。
- 车机端"点击图标退回桌面"多数是 Activity 启动链路异常（未捕捉的崩溃或 ANR 后被系统回收），先看 dropbox/logcat 再定位根因。

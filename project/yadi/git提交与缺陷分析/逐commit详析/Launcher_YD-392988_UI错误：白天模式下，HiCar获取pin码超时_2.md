# YD-392988 · 白天模式下 HiCar 获取 pin 码超时提示时 dock 为黑色（Launcher 侧）

- **提交**：`6207d507` | 2026-07-30 | dufan | Launcher | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392988，defs 无条目）

## 问题
与 `46c73e01` 同一单据：白天模式下 HiCar 获取 pin 码超时提示出现时，底部 dock 栏显示为黑色。本笔是配套的 Launcher 侧修复。

## 根因分析
`LinkActivity`（HiCar 连接/pin 码页）原先没有声明 `launchMode` 与 `taskAffinity`，默认与 Launcher 主页同属一个任务栈。由此 pin 码流程中 LinkActivity 的启动/超时提示会牵动整个 launcher 主任务的前后台状态，SystemUI 侧 `onTaskMovedToFront` 收到的事件在"主页任务"与"连接页任务"之间混淆，dock 的透明/不透明状态机被错误事件流打乱（详见 `46c73e01` 的 SystemUI 侧分析）。任务栈归属不清是状态判断错误的另一半根源。

## 关键代码修改
改动文件：application/Launcher/src/main/AndroidManifest.xml

```diff
--- application/Launcher/src/main/AndroidManifest.xml
         <activity
             android:name="com.yadea.launcher.function.link.LinkActivity"
             android:configChanges="screenSize|orientation|uiMode"
+            android:launchMode="singleTask"
+            android:taskAffinity="com.yadea.launcher.link"
             android:theme="@style/AppListTheme" />
```

## 为什么能修复
`taskAffinity="com.yadea.launcher.link"` + `launchMode="singleTask"` 让 LinkActivity 运行在独立任务栈中且全局单实例：pin 码流程的前后台切换不再与主页任务互相触发 `onTaskMovedToFront`，SystemUI 的 dock 状态机获得干净的事件流；与 SystemUI 侧"按 className 排除 LinkActivity"（`46c73e01`）双保险，dock 在 pin 码页恢复常规不透明底色。副作用：LinkActivity 独立成栈后，返回键/任务切换行为与原来略有差异（从 Link 页返回不再必然回到主页任务），需按交互稿验收；`configChanges` 已含 `uiMode`，昼夜切换不会重建该 Activity，主题资源取值仍随系统。

## 复盘与经验
- **全屏"流程页"应独立 taskAffinity**：连接/配对/弹码这类一次性流程页混在主任务栈里，会通过任务事件污染宿主应用（此处是 SystemUI dock）的状态机。
- **同一 UI 缺陷跨两个应用时，manifest 修复与监听方修复常常缺一不可**：只改 SystemUI 判断，任务事件仍会混淆；只改任务栈，SystemUI 的包名级判断仍会漏——两笔提交是同一方案的两半。
- **manifest 声明是最低成本的架构修正**：相比在代码里到处判断"这是不是 Link 页"，给 Activity 声明独立的任务身份从源头减少歧义。

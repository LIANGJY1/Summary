# SIR-6357 · 连续点开两个应用后下拉手势不回到 3D 桌面

- **提交**：`de507628` | 2026-08-25 | liujinfeng | CommonTools | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
连续打开两个应用后，在应用内执行下拉手势，退出动效正常播放，但最终没有回到 3D 桌面，停留原地。

## 根因分析
通用下拉控件 `LapseTouchHelper`（`component/CommonTools`）的下拉动效收尾逻辑里，`doOnEnd` 只回调了 `onExitListener.onExitComplete()`，把"退出后去哪"的责任交给了宿主应用的监听器实现。宿主若未在 `onExitComplete` 中主动发起回桌面的跳转（或依赖原页面自身 finish，但连开两个应用后栈顶/焦点关系已变化，桌面没有被重新带到前台），动效放完就什么都不会发生。也就是说该通用控件默认"人人都会自己回桌面"，缺少兜底动作，一旦某个接入方没实现或实现不完整，下拉就变成"空操作"。注意同批次 SIR-6613 修复中 `LapseTouchLayout` 也是这套下拉体系，说明该控件被多模块复用，缺陷影响面是所有接入方。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/utils/LapseTouchHelper.kt`

```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/utils/LapseTouchHelper.kt
@@ -180,6 +181,10 @@ class LapseTouchHelper(
                 onExitListener.onDragging(value, progress)
             }
             doOnEnd {
+                val homeIntent = Intent(Intent.ACTION_MAIN)
+                homeIntent.addCategory(Intent.CATEGORY_HOME)
+                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
+                mContext.startActivity(homeIntent)
                 onExitListener.onExitComplete()
             }
             start()
```

## 为什么能修复
修复把"回到桌面"下沉为通用控件自身的标准行为：下拉退出动效 `doOnEnd` 时主动发 `ACTION_MAIN` + `CATEGORY_HOME` 的 Intent（带 `FLAG_ACTIVITY_NEW_TASK`，因为 `mContext` 是应用/非 Activity 上下文，必须加此 flag 才能启动），确保无论宿主是否实现 `onExitComplete` 的跳转，系统都会把桌面切到前台。隐患：如果某宿主在 `onExitComplete` 里也会回桌面/finish 自己，可能出现双重跳转或时序竞争，后续应确认各接入方语义；另外在该 Helper 层感知不到桌面是否已在前台，返回键路径不受影响。

## 复盘与经验
- 通用控件里"动效播完"与"业务跳转"分离的设计容易产生"都以为对方做了"的断档；给控件补默认行为（回桌面）是最小修复，但更彻底的做法是明确契约：控件只管动效，或在文档/接口上强制宿主实现。
- `CATEGORY_HOME` Intent 从非 Activity 上下文发起必须带 `FLAG_ACTIVITY_NEW_TASK`，否则直接抛异常——车机多应用共用组件里这类代码要放在通用层测试过。
- "连续打开两个应用"才复现的 bug 提示任务栈/前台归属有状态残留，测试下拉手势时应包含多应用连续切换序列，而不是单应用内验证。

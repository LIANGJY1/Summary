# SIR-6710 · 蓝牙通话中打开拨号盘，挂断后拨号盘未隐藏

- **提交**：`88b4be80` | 2026-08-28 | caohongliang | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话（提交信息标注 D，以缺陷库为准）

## 问题
蓝牙通话时点击拨号键盘展开拨号盘，挂断电话后通话浮窗隐藏了，但拨号盘悬浮窗仍残留在屏幕上。

## 根因分析
`FloatCallWindow.showEmptyUI()` 是"无活跃通话"时隐藏主通话浮窗的统一入口（内部 `setVisibility(View.GONE)`）。而拨号盘是**独立的悬浮窗**（`hideDailPadView()` 控制），它的隐藏没有挂在 `showEmptyUI` 的路径上，而是依赖"实际通话挂断状态"的另一条链路——该链路在"通话中打开拨号盘→挂断"的时序下先于/异于浮窗隐藏触发（或被跳过），导致主浮窗与拨号盘的显隐不同步，拨号盘残留。修复按提交信息"保持和浮窗一致"：在浮窗隐藏的统一入口同步收起拨号盘。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
     @Override
     public void showEmptyUI() {
         LogUtils.d(TAG, "showEmptyUI~~~~~~~~~~~~~");
+        // 拨号盘是独立的悬浮窗，主通话浮窗隐藏时需要同步移除。
+        hideDailPadView();
         // 隐藏当前视图
         setVisibility(View.GONE);
```

## 为什么能修复
`showEmptyUI` 是浮窗回空态的必经之路，在此先 `hideDailPadView()` 保证"主浮窗消失"与"拨号盘消失"成为原子动作，不再依赖挂断状态链路各自的时序。改动一行，无副作用；即使拨号盘本就未展开，重复 hide 也是幂等操作。

## 复盘与经验
- 同一界面的子浮窗/子面板（拨号盘、键盘、二级弹层）应跟随父容器生命周期的统一入口显隐，而不是各自监听底层状态——否则时序差异必然产生残留。
- "挂断状态"与"浮窗空态"是两个信号源，UI 收起逻辑要挂在后者（离用户最近的聚合态）上。
- 悬浮窗类 UI 的回归测试要覆盖"展开附属面板 → 状态突变"的组合场景。

# SIR-8244 · 行车仪表界面 CarPlay 来电浮窗位置置顶

- **提交**：`73d60fb4` | 2026-09-12 | ljl | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
CarPlay 来电浮窗在行车仪表界面（仪表屏）显示时贴着屏幕最顶端，与中控屏上的位置不一致，看起来被"置顶"。

## 根因分析
`CarPlayCallWindow` 用同一个 `mLayoutParams` 对象跨屏复用（中控 Display 0 / 仪表 Display 2）。在中控屏上，overlay 窗口可用区域本身从状态栏下方开始，`y = TOP_MARGIN_DP(8dp)` 即得到"距屏幕顶 63px"的 UX 位置；但仪表屏（Display 2）没有系统状态栏装饰，WMS 不会自动把窗口下移，同一份 `y=8dp` 挂到仪表屏就直接顶到屏幕上沿。原注释也只按默认屏假设写死了"距顶 63px"的推导，未考虑无状态栏屏的差异——属于"同一布局参数在不同 Display 上语义不同"的跨屏适配缺口。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java`
```diff
             render(callMap);
             mRootView.setVisibility(View.VISIBLE);
             WindowManager targetWM = windowManagerForDisplay(displayId);
+            // mLayoutParams 跨屏复用：挂仪表屏时补偿状态栏高度，保持视觉位置与默认屏一致（SIR-8244）。
+            // 以实际挂载的 WindowManager 判断（而非 displayId）：仪表屏不可用时回落默认屏，此时不加补偿
+            mLayoutParams.y = targetWM == mDashboardWindowManager
+                    ? StatusBarUtil.getStatusBarHeight(mContext) + dpToPx(TOP_MARGIN_DP)
+                    : dpToPx(TOP_MARGIN_DP);
             if (mShowing && mActiveWindowManager == targetWM) {
                 // 同屏刷新，复用现有窗口
                 return;
```
同步把 `TOP_MARGIN_DP` 注释修正为"默认屏 8dp 间隙；仪表屏无状态栏装饰需挂载时补偿"，`createLayoutParams` 的注释改为"距状态栏 8dp"。

## 为什么能修复
在挂载前按"实际使用的 WindowManager 是否为仪表屏"设置 `y`：仪表屏补偿一个状态栏高度再加 8dp 间隙，视觉位置与中控屏一致；中控屏维持原 8dp。用 `targetWM == mDashboardWindowManager` 而非 displayId 判断，保证仪表屏不可用回落默认屏时不误加补偿，逻辑闭环。隐患：状态栏高度取自中控屏（`mContext`），若两块屏状态栏规格不同，补偿值仍是近似；`mLayoutParams` 跨屏共享一个 y 值在"同帧跨屏切换"场景依赖 show 流程每次重算，后续新增挂载路径须记得复用该赋值。

## 复盘与经验
- 多屏车机上，WindowManager 布局参数不可默认跨屏等价：有无状态栏装饰、可用区起点都不同，跨屏挂载前必须按目标屏重算位置。
- "以实际挂载的 WindowManager 判断"优于以 displayId 判断，能把回退逻辑天然纳入同一分支。
- 注释里写死"距屏幕顶 63px"这类推导时，应注明其成立前提（默认屏、有状态栏），否则后来者会照搬到所有屏。

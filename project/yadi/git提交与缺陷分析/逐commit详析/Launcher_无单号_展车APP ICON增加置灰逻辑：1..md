# 无单号 · 展车 APP ICON 置灰逻辑（P 档 gate）

- **提交**：`e184eae7` | 2026-08-20 | liang-jy | Launcher | feature
- **关联单**：无

## 需求/目标
应用中心里的展车 App 入口按档位门控：非 P 档时图标置灰、点击 toast 提示"P 档可用"；P 档正常显示与进入。

## 实现结构
改动 7 个文件：`Constants.java` 新增展车 App 包名常量；`VehicleService.java` 缓存档位信号 `mCachedActualGear`（volatile，0=P / 非0=非P / -1=未就绪）并暴露 getter；`AppRecyclerAdapter.kt` 在 onBindViewHolder 对展车包名做置灰（换 disable 图标）并新增 `refreshShowcaseApp()` 精准刷新单项；`AppListFragment.kt` 注册 `ICarChangeEventCallback` 监听 `ENERGY_PCU_ACTUALGEAR`，档位变化时 runOnUiThread 刷新，点击禁用项时 toast；新增置灰图标 png、中英文案 `showcase_app_need_p_gear`。
数据流：车控信号 → VehicleService 缓存+事件分发 → Fragment 回调 → Adapter notifyItemChanged → 图标置灰；点击路径由原有的 itemToast 回调拦截提示。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/services/VehicleService.java
+    /** 缓存最新的档位信号(0=P档, 非0=非P档, -1=未就绪), 供展车App置灰判断直接取用 */
+    private volatile int mCachedActualGear = -1;
...
+            if (propertyId == CarPropertyIds.ENERGY_PCU_ACTUALGEAR && event.getValue() instanceof Number) {
+                mCachedActualGear = ((Number) event.getValue()).intValue();
+            }
```
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/adapter/AppRecyclerAdapter.kt
+        if (item.packageName == Constants.SHOWCASE_PACKAGE) {
+            val isPark = isParkGear()
+            item.isGrayShow = !isPark
+            item.displayIcon = null
+            if (!isPark) {
+                mDrawable = mContext.getDrawable(R.drawable.icon_show_case_app_disable)
+            }
+        }
```
实现讲解：采用"缓存最新信号 + 事件驱动刷新"组合：VehicleService 仿照已有的 mCachedSmartLightState 模式缓存档位，绑定时不做跨进程查询而是直接读缓存，保证滚动列表不卡；档位变化只 notifyItemChanged 单项而非整表刷新。点击拦截复用既有 itemToast 回调按包名分发文案。

## 复盘与要点
- 可复用手法："Manager 缓存车控信号 + volatile getter"是处理列表 UI 依赖高频车信号的标准姿势，避免 binder 同步查询。
- `isParkGear()` 判断 `cachedActualGear == 0`，未就绪(-1)也会被判为非 P 档置灰——开机初期信号未到时先置灰是偏安全的降级，但可能短暂误灰。
- 置灰用整张预置 disable png 而非 colorFilter，需为每个入口单独出图；图标规则多了以后可改渲染层统一置灰。

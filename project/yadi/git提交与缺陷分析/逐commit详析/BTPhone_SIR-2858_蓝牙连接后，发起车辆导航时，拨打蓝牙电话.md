# SIR-2858 · 导航中拨打蓝牙电话 HUD 不显示蓝牙电话信息
- **提交**：`fd580c04` | 2026-07-20 | hedeyuan | BTPhone | bugfix（新功能补齐）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙连接后车辆发起导航的同时拨打蓝牙电话，HUD 上不显示蓝牙电话信息。

## 根因分析
缺陷库根因"新功能，拨打蓝牙电话，未将蓝牙信息投屏到仪表屏"。原 `FloatCallWindow` 只面向单一显示（仪表屏 Display 2），来电/通话中视图由 `FloatWindowManager` 添加到当前 display；HUD 屏（Display 3）从未被投屏蓝牙电话视图，属于功能缺失而非逻辑错误。需要一套双屏（dual display）机制：来电时同时向 Display 2 投仪表布局、向 Display 3 投 HUD 布局，且来电/去电/通话中/挂断各状态都要双屏同步。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`、`floatview/FloatWindowManager.java`、`res/layout/float_calling_window.xml`、`res/layout/float_hud_window.xml`、`res/layout/float_outgoing_window.xml`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
+    // 【新增】HUD屏（Display 3）配套视图 —— 简单FrameLayout，不由Presenter管理
+    private FrameLayout mHudCompanionView;
@@ (来电视图 inflate)
+        boolean dualDisplayAvailable = mFloatWindowManager.isDualDisplayAvailable();
+        if (dualDisplayAvailable) {
+            // 1. 仪表屏（Display 2）：inflate到自身
+            inflateInComingMeterView();
+            // 2. HUD屏（Display 3）：创建简单FrameLayout，inflate 来电HUD布局
+            mHudCompanionView = new FrameLayout(mContext);
+            inflateHudLayoutInto(mHudCompanionView, false, true);
+            mFloatWindowManager.addHudCompanionView(mHudCompanionView);
+        } else {
+            inflateInComingMeterView();   // 单屏模式：保持原有逻辑
+        }
```

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatWindowManager.java
+    public void addHudCompanionView(View hudView) {   // 将HUD配套视图添加到 Display 3
+        ...
+        mDualDisplayMode = true;
+        ... // 通过 HUD 专用 WindowManager addView
+    }
+    private void removeHudCompanionView() {
+        // 【关键】先设GONE确保视觉立即消失，解决Display 3 ViewRoot未就绪时removeView不可靠的问题
+        ...
+    }
```

## 为什么能修复
建立了 Display 2（仪表）+ Display 3（HUD）双通道投屏：`FloatCallWindow` 在来电/去电/通话布局装配时检测 `isDualDisplayAvailable()`，把轻量 HUD 布局通过 `FloatWindowManager.addHudCompanionView` 挂到 Display 3 的 WindowManager 上，挂断时 `removeHudCompanionView` 对称移除；布局层新增/调整 `float_hud_window.xml` 适配 HUD 尺寸。缺陷库 sol 写"displayId4"，而代码与提交消息均为 Display 3，以 diff 为准。隐患：HUD companion 视图不走 Presenter 状态管理，状态更新靠主视图同步，双视图数据一致性依赖每次 inflate 重建。

## 复盘与经验
- 车机多屏（仪表/ HUD/副驾）特性要在一开始就把 WindowManager 投屏抽象成可扩展接口；事后补"第二块屏"往往要在每个视图装配点插 if-else。
- 移除跨屏视图时先 setVisibility(GONE) 再 removeView，可规避目标屏 ViewRoot 未就绪导致的"视图残留"，是双屏开发的实用细节。
- 缺陷库记录的 displayId 与代码不一致时以代码为准，同时说明缺陷单是按"测试观察"而非"实现方案"填写的。

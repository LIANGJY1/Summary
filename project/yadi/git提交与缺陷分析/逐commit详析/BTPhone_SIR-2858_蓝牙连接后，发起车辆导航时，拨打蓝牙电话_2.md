# SIR-2858 · HUD 投屏修正：目标屏由 Display 3 改为 Display 4
- **提交**：`6bfdadb4` | 2026-07-21 | hedeyuan | BTPhone | bugfix（前次修复的纠错）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
`fd580c04` 初版双屏投屏用了 Display 3 作为 HUD 屏，但整车 HUD 的实际 displayId 是 4，导致蓝牙电话信息仍然没有出现在 HUD 上（投到了不存在的屏/被丢弃）。

## 根因分析
初版实现按假设写死了 `displayId == 3`：`initMultiDisplayWindowManagers` 遍历 `DisplayManager.getDisplays()` 时以 `displayId == 3` 匹配并初始化 `mHudWindowManager`，`addHudCompanionView`/`removeHudCompanionView` 中 `multiViewMap` 也以 key=3 存取。实车 HUD 挂在 displayId 4 上，初始化条件永不命中，`mHudWindowManager == null`，`addHudCompanionView` 直接 abort——这就是上一提交后 HUD 仍不显示的直接原因。displayId 是系统级布局约定，开发期靠猜不靠枚举实测。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`、`floatview/FloatWindowManager.java`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatWindowManager.java
-                    // 初始化 Display 3（HUD）
-                    if (displayId == 3) {
+                    // 初始化 Display 4（HUD）
+                    if (displayId == 4) {
@@ addHudCompanionView / removeHudCompanionView
-            List<View> viewList = multiViewMap.get(3);
+            List<View> viewList = multiViewMap.get(4);
```
配套：`FloatCallWindow` 中所有日志与单屏分支 `currentDisplayId == 3` 判断同步改为 4；`isDualDisplayAvailable` 增加两个 WindowManager 是否为空的明细日志，便于下次定位。

## 为什么能修复
displayId 从假设值改为实车真实值后，`mHudWindowManager` 能被正确初始化，HUD 视图真正 add 到 HUD 屏；`multiViewMap` key 一并同步，添加/移除路径一致，不会留下悬挂视图。本次还补充了诊断日志（哪个 WindowManager 为 null），降低同类错误的再次排查成本。

## 复盘与经验
- 多屏 displayId 必须在目标硬件上用 `DisplayManager.getDisplays()` 实测枚举后写死或做成配置，不能沿用开发板/上一代车型的编号。
- 幻觉屏 ID 的失败模式是"静默不显示"（init 不命中→manager 为 null→abort），第一次提交时就把 `isDualDisplayAvailable` 拆成明细日志，本可以提前一轮发现。
- 同一常量（屏 ID）散落在 init/map key/分支判断多处，改一次要全量搜索——应收敛为 `DISPLAY_HUD = 4` 常量。

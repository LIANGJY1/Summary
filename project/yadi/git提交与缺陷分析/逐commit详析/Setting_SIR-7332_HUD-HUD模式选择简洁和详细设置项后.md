# SIR-7332 · HUD 模式选择简洁/详细后设置项回弹

- **提交**：`740db676` | 2026-09-03 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
HUD 模式选择"简洁"或"详细"（以及雪地模式日/夜）设置项后，选中的设置项先切过去又"回弹"回原状态。

## 根因分析
HUD 模式卡片由"预览卡"（`ivHudModeSimplePreview / ivHudModeDetailedPreview / ivHudSnowDailyPreview / ivHudSnowModeOnPreview`）与配套的圆形视图（`ivHudModeSimpleCircle / ivHudModeDetailedCircle / ivHudSnowDailyCircle / ivHudSnowModeOnCircle`）组成。状态确认回调里只对 Preview 卡调用了 `cancelCardRebound()` 取消回弹动画，Circle 视图没有取消——卡片组内一部分已定格新状态，Circle 仍带着未取消的回弹动画弹回旧视觉，整体观感即"设置项回弹"（缺陷库根因"UI回弹bug"）。这是典型的"动画取消调用点漏掉一半视图"问题。

## 关键代码修改
改动文件：HudFragment.kt（仅 +4 行）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/HudFragment.kt（HUD 模式确认回调，雪地模式分支同构）
         logObserve("HUD mode confirmed: $gear")
         mBinding.driverHudLayout.ivHudModeSimplePreview.cancelCardRebound()
         mBinding.driverHudLayout.ivHudModeDetailedPreview.cancelCardRebound()
+        mBinding.driverHudLayout.ivHudModeSimpleCircle.cancelCardRebound()
+        mBinding.driverHudLayout.ivHudModeDetailedCircle.cancelCardRebound()
         updateHudModeCards(gear)
```

## 为什么能修复
确认状态时对同一卡片组的全部成员（Preview + Circle）统一 `cancelCardRebound()`，残留回弹动画被清除，`updateHudModeCards` 定格到新状态后不再有任何视图弹回，回弹现象消失。改动零逻辑风险；隐患是"哪些视图属于同一动画组"仍靠人工枚举，下次再新增卡片成员还会漏，宜在 `updateHudModeCards` 内部统一遍历取消。

## 复盘与经验
- 补丁式动画修复（哪里弹就 cancel 哪里）治标不治本：应把"取消回弹"收敛到状态刷新入口，对该卡片组所有子视图统一执行。
- 复合视图（预览+圆点/背景+前景）做联动动画时，任何"取消/重置"操作必须覆盖组内全部成员，review 时按布局层级清点。
- `hudSnowModeStateTemp / mapInfoGearStateTemp` 这种"临时状态 + 确认后清理"的防抖结构里，清理逻辑（cancel 动画、置 null）容易只写一半，值得抽成单个 confirm 函数。

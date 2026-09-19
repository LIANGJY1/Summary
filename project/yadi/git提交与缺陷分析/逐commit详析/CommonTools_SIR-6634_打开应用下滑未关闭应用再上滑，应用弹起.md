# SIR-6634 · 下滑未关闭应用再上滑，应用弹起
- **提交**：`87cd64ae` | 2026-09-01 | caohongliang | CommonTools | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
打开应用后下滑（未达阈值，应用回弹未关闭），随后再上滑时，应用视图出现异常弹起/跳动。

## 根因分析
通用组件 `LapseTouchHelper`（下滑退出应用手势助手）在 `ACTION_UP` 且未超过关闭阈值时调用回弹动画 `animationToStart(deltaY)`——参数传的是本次手势的增量 `deltaY`（`currentY - initialY`），而不是视图当前的实际位移。回弹动画以错误起点启动：当视图已经停在下移位置（前次下滑的 translationY 未归零），`animationToStart` 却从增量值起算，导致动画起始位与当前视图位置脱节，再上滑时视图从错误位置突变"弹起"。

## 关键代码修改
改动文件：`component/CommonTools/src/main/java/com/yadea/common/utils/LapseTouchHelper.kt`
```diff
--- component/CommonTools/src/main/java/com/yadea/common/utils/LapseTouchHelper.kt
@@ onTouchEvent() ACTION_UP 未过阈值分支
                     if (isOverThreshold) {
                         isTriggered = true
                         animationToExit(currentY)
                     } else {
-                        animationToStart(deltaY)
+                        animationToStart(onExitListener.getCurrentTranslation())
                     }
```

## 为什么能修复
`getCurrentTranslation()` 返回视图当前真实位移，回弹动画从实际所在位置平滑过渡回 0，起止点一致，不再跳变。与同函数内 `ACTION_MOVE` 跟手逻辑（`getCurrentTranslation() + deltaY`）以及 `animationToExit(currentY)` 的取值口径统一。单行修改，无副作用，仅需回归各应用内下滑取消场景。

## 复盘与经验
- 动画起点必须取视图当前状态（translation/scroll），而不是手势增量或事件坐标——两者坐标系不同，混用必然跳变。
- 通用手势组件的参数命名（start/delta）易误导调用方，起名应体现坐标系（如 startTranslationY）。
- "未关闭的回弹"是下滑退出交互的高频边界场景，测试用例应显式包含"滑一半松手 → 反向滑动"。

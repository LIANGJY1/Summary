# SIR-6377 · 天气出现&消失动效多了淡入淡出与需求不符

- **提交**：`91016f8c` | 2026-08-25 | liujinfeng | CommonTools | bugfix（动效资源类）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
天气应用下拉退出时，界面在位移之外还出现了透明度渐变（淡出）效果，与需求的"纯位移"动效不符。

## 根因分析
公共下拉组件 `LapseTouchLayout`（component/CommonTools 的 `com.yadea.common.widgets`）在拖拽回调 `onDragging(translationY, progress)` 里除了设置 `this.translationY` 外，还写了 `this.alpha = 1 - progress * 0.8f`——随下拉进度把透明度从 1 降到 0.2；`onExitCancel()`（取消退出回弹）里也同步恢复 `alpha = 1f`。这段透明度动画是全局组件行为，天气应用的下拉退出随之带上淡入淡出，与需求动效（只位移不透明）冲突。缺陷库根因"下拉动效增加了透明度动画"。修复即注释掉两处 alpha 赋值，保留纯 translationY 位移。

## 关键代码修改
改动文件：component/CommonTools/src/main/java/com/yadea/common/widgets/LapseTouchLayout.kt（+2/-2）
```diff
--- component/CommonTools/src/main/java/com/yadea/common/widgets/LapseTouchLayout.kt
@@ onExitCancel()
         this.translationY = 0f
-        this.alpha = 1f
+//        this.alpha = 1f
         currentTranslationY = 0f
@@ onDragging()
         currentTranslationY = translationY
         this.translationY = translationY
-        this.alpha = 1 - progress * 0.8f
+//        this.alpha = 1 - progress * 0.8f
```

## 为什么能修复
拖拽过程只改 `translationY`，下拉退出与回弹均为纯位移，透明度恒为 1，动效与需求一致。隐患：这是公共组件，其他接入 `LapseTouchLayout` 的应用若依赖"下拉渐隐"效果会被一并去掉——修复采用注释而非删除/条件开关，属于全局行为变更，未给各接入方留配置项；若后续有应用要恢复渐隐，应抽出 `enableAlphaOnDrag` 之类的属性而不是恢复硬编码。

## 复盘与经验
- 公共动效组件的每一个视觉效果（位移/透明/缩放）都应可配置或按需求裁剪，硬编码的组合效果会让某个应用"被动拥有"不需要的动效。
- 动效类缺陷单（"与需求动效不符"）往往没有技术含量，但暴露的是设计稿-实现对照缺失：动效验收需要录屏比对。
- 用注释停用代码不如删除+说明，或在组件层留开关；注释代码容易被后人"顺手恢复"造成回归。

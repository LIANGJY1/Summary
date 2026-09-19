# SIR-7623 · 打开氛围灯界面滚动位置自动跳到最顶部
- **提交**：`d86091cd` | 2026-09-07 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 地图导航（JSON 记录域如此，实际属车控车设/灯光设置页面）

## 问题
打开氛围灯设置界面时，页面滚动位置自动移动到最顶部，用户停留的浏览位置丢失（必现）。

## 根因分析
`fragment_light_adjustment.xml` 的页面滚动容器用的是原生 `androidx.core.widget.NestedScrollView`。该容器在任何 relayout / 子 View 刷新 / 焦点变化时会触发 `scrollTo(0,0)` 一类的位置重置——氛围灯页面内有大量动态 UI 刷新（色温滑条、亮度控件、开关等），刷新引发布局重算后 ScrollView 被拉回顶部，属于"UI 刷新导致滚动位置重置"的经典问题（缺陷库 rc：ui 刷新问题）。修复方式是替换为项目自定义的 `com.yadea.setting.ui.widget.SmartNestedScrollView`（更早的 feature 提交 e82137df 已引入该控件）：它通过 `onInterceptTouchEvent` 区分"用户手动滚动"与"代码触发滚动"，只在用户拖动时更新位置并记录 `lastUserScrollY`，并重写 `scrollTo` 拦截非用户触发的位置重置，从而在 UI 刷新后保住滚动位置。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/fragment_light_adjustment.xml（仅 XML 容器替换，2 行）
```diff
--- application/Setting/src/main/res/layout/fragment_light_adjustment.xml
@@ -3,7 +3,7 @@
-    <androidx.core.widget.NestedScrollView
+    <com.yadea.setting.ui.widget.SmartNestedScrollView
         android:id="@+id/lightNestedScrollView"
         android:layout_width="match_parent"
@@ -96,6 +96,6 @@
-    </androidx.core.widget.NestedScrollView>
+    </com.yadea.setting.ui.widget.SmartNestedScrollView>
```
（配套控件 `SmartNestedScrollView.kt` 位于 application/Setting/src/main/java/com/yadea/setting/ui/widget/，核心为 `userScrollAllowed` 标志 + `scrollTo` 重写 + `lastUserScrollY` 恢复基准。）

## 为什么能修复
自定义容器把"滚动位置的变更权"收归用户手势：代码/布局刷新引发的 `scrollTo` 被拦截，relayout 后按 `lastUserScrollY` 恢复，页面不再被拉回顶部。这类替换是纯局部改动，不影响其他页面；隐患是自定义 ScrollView 拦截了所有程序化滚动，若未来有"进入页面自动定位到某设置项"的需求，需要为该控件显式开白名单接口。

## 复盘经验
- 含大量动态控件的长页面，不要裸用 NestedScrollView——刷新即回顶是高发问题，项目应有统一的防回顶滚动容器。
- "区分用户滚动与程序滚动"（onInterceptTouchEvent 打标 + scrollTo 重写）是通用解法，值得沉淀为标准控件。
- 此类修复只需换 XML 标签即可全局受益，说明把防御逻辑做进公共控件比每页打补丁划算；后续 SIR-7317 又基于该控件继续加固，验证了这一路线。

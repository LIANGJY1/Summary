# 无单号 · [SIR-XXX] 弹窗背景改为直角
- **提交**：`c474cf9e` | 2026-09-10 | dufan | CommonTools | feature（UI 样式调整）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
按新的 UI 规范把通用弹窗背景从"顶部圆角"改为直角。

## 实现结构
仅改动 `component/CommonTools/src/main/res/drawable/bg_dialog_dim.xml`（-3）：shape drawable 中删除 `corners` 节点（顶部左右 36dp 圆角），保留半透明遮罩底色 `bg_mask_dialog`。由于这是 CommonTools 公共组件里的背景资源，所有引用该 drawable 的弹窗一次性变更为直角。

## 关键代码
```xml
<!-- component/CommonTools/src/main/res/drawable/bg_dialog_dim.xml -->
 <shape xmlns:android="http://schemas.android.com/apk/res/android">
     <solid android:color="@color/bg_mask_dialog" />
-    <corners
-        android:topLeftRadius="@dimen/dp_36"
-        android:topRightRadius="@dimen/dp_36" />
 </shape>
```

实现讲解：删掉 corners 节点即等效直角，零代码改动。因为是公共组件级 drawable，一行删除即可全局生效——这正是公共样式资源集中管理的收益（也可能是风险，见下）。

## 复盘与要点
- **类型标注**：纯 UI 资源调整（shape drawable），一行删除。
- **公共组件改样式的双刃剑**：改一处全局生效效率高，但所有使用方（不同业务弹窗）会同时被改，若个别弹窗仍需圆角需另建资源，提交前应确认影响面。
- **细节**：该文件行尾缺 newline（`\ No newline at end of file`），属于历史遗留的小卫生问题。

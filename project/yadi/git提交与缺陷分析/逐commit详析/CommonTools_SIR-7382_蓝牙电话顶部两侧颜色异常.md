# SIR-7382 · 蓝牙电话顶部两侧颜色异常
- **提交**：`09f4b886` | 2026-09-04 | caohongliang | CommonTools（实际改动在 BTPhone） | bugfix（背景设置）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
蓝牙电话主界面顶部左右两侧露出异常颜色，与整体背景不衔接。

## 根因分析
`activity_main.xml` 中，`bg_main` 背景原本设在内容层的 `ConstraintLayout` 上，而该内容层设置了 `android:elevation="32dp"`：elevation 使其浮于父布局之上并产生阴影轮廓，父根布局自身无背景（透明），窗口底层颜色从顶部两侧透出，形成异常色块。背景层级放错（放在带 elevation 的子容器而非根布局）是"背景设置错误"的具体形态。

## 关键代码修改
改动文件：`application/BTPhone/src/main/res/layout/activity_main.xml`

```diff
--- application/BTPhone/src/main/res/layout/activity_main.xml
     xmlns:tools="http://schemas.android.com/tools"
     android:layout_width="match_parent"
     android:layout_height="match_parent"
+    android:background="@drawable/bg_main"
     android:paddingTop="@dimen/dp_10"
     android:clickable="true"
     android:focusable="true">
@@ 
     <androidx.constraintlayout.widget.ConstraintLayout
         android:layout_width="match_parent"
         android:layout_height="match_parent"
-        android:background="@drawable/bg_main"
         android:elevation="32dp">
```

## 为什么能修复
`bg_main` 从带 elevation 的内容容器上移到根布局，整个窗口被同一背景完整铺满，顶部两侧不再露出底层颜色；内容容器的 elevation 阴影效果保留、只叠加在统一背景之上。单属性迁移，无逻辑风险。

## 复盘与经验
- 带 `elevation` 的容器会把背景一起抬升渲染并投射阴影，全屏底色应放在根布局（或 Window background），不要放在有 Z 轴的内容层。
- "顶部/四周露异色"类视觉问题，先检查背景放在哪一层、父容器是否透明、有无 elevation 阴影参与，三处足够定位绝大多数案例。
- 模块标注为 CommonTools 但实际改动在 BTPhone 布局，元数据模块字段与实际代码位置存在偏差时以 diff 为准。

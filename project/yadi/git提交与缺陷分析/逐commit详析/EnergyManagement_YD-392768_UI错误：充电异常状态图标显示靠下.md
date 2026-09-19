# YD-392768 · UI错误：充电异常状态图标显示靠下

- **提交**：`2bcf7533` | 2026-06-29 | liqingqing | EnergyManagement | bugfix（布局 1 行修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
能耗管理页"充电异常"状态图标（`@drawable/broken`）相对旁边状态文字偏低，与 UI 设计稿不符。

## 根因分析
`activity_main.xml` 中该 ImageView 以 `app:layout_constraintBottom_toBottomOf="@id/tv_charge_gun_connected_status"` 与状态文字底边对齐，但额外叠加了 `android:translationY="4dp"` 的 y 方向位移——图标被人为下压 4dp，视觉"靠下"。这是典型的"补偿式微调残留"：多半是当初为凑某种视觉而加的偏移，设计稿更新后未回收，`translationY` 这类绘制层属性不在约束体系内，走查时也不易被发现。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/layout/activity_main.xml

```diff
--- application/EnergyManagement/src/main/res/layout/activity_main.xml
@@ 删除 y 向偏移，改用对称 padding
             android:layout_height="48dp"
             android:layout_marginStart="12dp"
+            android:padding="@dimen/dp_4"
             android:contentDescription="@null"
             android:src="@drawable/broken"
-            android:translationY="4dp"
             android:visibility="gone"
```

## 为什么能修复
去掉 `translationY="4dp"` 后图标回到约束指定的对齐线；同时补上 `padding=4dp`（48dp 容器内缩 4dp，图标绘制区 40dp），既保持图标视觉尺寸不变，又让"图标-文字"的基线关系由约束统一接管，不再受绘制层位移干扰。改动仅 1 文件 1 处 2 行，无任何逻辑风险；唯一提醒是 padding 会同时影响 x/y 方向，若原图标水平位置依赖外层 margin，需确认视觉无横向偏移（本例 marginStart=12dp 未变，风险极低）。

## 复盘与经验
- **`translationX/Y` 是视觉微调的"隐形债"**：它脱离约束与布局参数体系，review 与走查工具都不易覆盖；能用 padding/margin/gravity 表达的对齐不要用 translation。
- **图标对齐优先交给约束**：`constraintBottom_toBottomOf` 一旦生效，任何额外位移都要能说出存在理由，否则删。
- **1 行 bug 的修复价值不在行数**：本例 2 行改动换来"对齐逻辑回归约束体系"，比调 8 个 margin 更可维护。

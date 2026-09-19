# SIR-7808 · 能量中心停止充电按钮距离标题略近

- **提交**：`b877182c` | 2026-09-11 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心
- **注意**：缺陷库根因写的是"松手时偏移量归零、拖动中重复刷新文字造成跳变"，但本提交实际 diff 只改了布局间距/高度，与缺陷库描述的根因/方案不符，以 diff 实际为准（缺陷库内容疑似串单或对应另一次拖动吸附优化）。

## 问题
能量中心界面"停止充电"状态文案（`tv_charge_gun_connected_status`）与上方标题距离过近，视觉间距不足。

## 根因分析
`application/EnergyManagement/src/main/res/layout/activity_main.xml` 中 `tv_charge_gun_connected_status` 的 `layout_height` 为 `wrap_content`，该 TextView 文字行高较矮，上方只有 `8dp` 的 marginTop，与标题行视觉间距偏小。由于高度是 wrap_content 且无 `gravity`，文字贴着自身行框顶部，进一步压缩了与标题的视觉距离。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/layout/activity_main.xml`
```diff
         <TextView
             android:id="@+id/tv_charge_gun_connected_status"
             android:layout_width="wrap_content"
-            android:layout_height="wrap_content"
+            android:layout_height="58dp"
             android:layout_marginTop="8dp"
             android:fontFeatureSettings="tnum, lnum"
+            android:gravity="start|center_vertical"
             android:text="@string/charge_gun_connected_status"
             android:textAppearance="@style/ChargeGunConnectedStatus"
             android:visibility="gone"
```

## 为什么能修复
把 TextView 高度固定为 `58dp` 并加 `gravity="start|center_vertical"`，文字在更高的行框内垂直居中，与标题之间自然留出视觉留白，无需改动 marginTop。副作用：固定高度后该行始终占 58dp（原本 visibility 为 gone 时本就不占位，可见时文字居中显示），对整体布局无溢出风险。

## 复盘与经验
- "间距略近"类 UI 缺陷，与其调 margin，不如给文本行固定设计稿高度并垂直居中，一次调整即与切图规范对齐。
- 复合文案（`fontFeatureSettings="tnum, lnum"` 等宽数字）行高常与中文字体不一致，wrap_content 容易产生视觉偏差。
- 缺陷库登记的根因与实际修复代码可能脱节，复盘时应以 diff 为准，避免被错误根因误导。

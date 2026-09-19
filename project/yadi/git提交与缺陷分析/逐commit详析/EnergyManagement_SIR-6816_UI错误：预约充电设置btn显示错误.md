# SIR-6816 · 预约充电设置按钮图标显示错误

- **提交**：`4d3a251d` | 2026-08-28 | liqingqing | EnergyManagement | bugfix（资源替换）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互（提交信息无等级标注，以缺陷库为准）

## 问题
能量中心预约充电设置的按钮图标显示错误（用了旧图/错误的切图），与 UI 设计稿不符。

## 根因分析
"预约充电设置"按钮通过主题包装 drawable `open_charge_detail_theme.xml`（日/夜两个限定符目录）引用底层图标 `open_charge_detail`。该底层图标资源内容是旧版切图，UI 更新时设计稿给出的新切图没有落地，导致按钮显示错误图标。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/drawable/open_charge_detail_theme.xml、drawable-night/open_charge_detail_theme.xml、drawable-mdpi/open_charge_detail2.png（新增）、drawable-night-mdpi/open_charge_detail2.png（新增）
```diff
--- application/EnergyManagement/src/main/res/drawable/open_charge_detail_theme.xml
-    android:src="@drawable/open_charge_detail" />
+    android:src="@drawable/open_charge_detail2" />

--- application/EnergyManagement/src/main/res/drawable-night/open_charge_detail_theme.xml
-    android:src="@drawable/open_charge_detail" />
+    android:src="@drawable/open_charge_detail2" />
（新增 drawable-mdpi 与 drawable-night-mdpi 两个目录下的 open_charge_detail2.png，二进制图片更新，日/夜各一份）
```

## 为什么能修复
日/夜两层主题包装 drawable 统一切换到新切图 `open_charge_detail2`（含夜间版本），按钮在两种模式下均显示正确图标。改动仅资源引用与图片替换，无逻辑风险。

## 复盘经验
- UI 切图落地要有核对清单：日间/夜间 × 各密度目录成套添加，漏掉任一限定符就会出现"某模式/某机型图标错"。
- 通过 theme 包装层（`*_theme.xml`）引用底层图标的间接结构便于整体换图，但两层命名（theme ↔ 实体图）要保持对应关系清晰。

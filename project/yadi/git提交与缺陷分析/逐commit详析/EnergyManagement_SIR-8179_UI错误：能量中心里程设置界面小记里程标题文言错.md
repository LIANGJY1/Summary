# SIR-8179 · 能量中心里程设置界面"小记里程"标题文言错误

- **提交**：`744f5ab4` | 2026-09-11 | liqingqing | EnergyManagement | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 能量中心

## 问题
能量中心里程设置界面中，"小记里程"标题文案与需求不一致（实现写成了"小计里程"），属文言（文案）错误。

## 根因分析
`application/EnergyManagement/src/main/res/values/strings.xml` 中 `subtotal_mileage_title` 的值被写成"小计里程"，而产品术语表/设计稿定义的是"小记里程"。变量名 `subtotal`（小计）误导了实现者按英文直译写了文案，且该 string 标了 `translatable="false"`（不翻译、纯中文写死），多语言评审也不会触碰，错误一路带到 UI。

## 关键代码修改
改动文件：`application/EnergyManagement/src/main/res/values/strings.xml`
```diff
-    <string name="subtotal_mileage_title" translatable="false">小计里程</string>
+    <string name="subtotal_mileage_title" translatable="false">小记里程</string>
```

## 为什么能修复
文案与产品术语对齐，一处资源修改全局生效，无任何逻辑风险。注意 `subtotal_mileage_title` 资源名仍是"小计"语义，仅为内部 key 不影响显示，但存在认知误导。

## 复盘与经验
- 文案类缺陷的源头常是"按变量名/英文名直译"而不是抄需求术语表；资源 value 必须以设计稿/术语表为准。
- `translatable="false"` 的中文写死字符串脱离了翻译评审流程，更需要与设计稿逐字比对。
- 资源 key 命名与最终文案语义不一致（subtotal vs 小记）会持续误导后续维护，命名时尽量贴近业务术语。

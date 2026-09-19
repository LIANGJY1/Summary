# SIR-1478 · 导航栏音乐进度条配色与背景更新

- **提交**：`246f7970` | 2026-07-07 | duanlonglong | SystemUI | **UI 需求变更类提交（非代码缺陷）**
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互 · 根因"UI需求变更"，方案"更新UI"

## 问题
导航栏音乐进度条 UI 与新版设计稿不符（进度渐变色为旧版橙色，底图为旧切图）。

## 根因分析
无代码逻辑缺陷。缺陷库明确记录根因为"UI 需求变更"，即设计稿迭代导致的资源更新，非功能 bug。

## 关键代码修改
改动文件：
- `application/SystemUI/src/main/res/drawable/seekbar_progress.xml`（渐变色更新）
- `application/SystemUI/src/main/res/drawable-mdpi/icon_seekbar_bg.png`（背景切图替换，二进制资源）

```diff
// --- application/SystemUI/src/main/res/drawable/seekbar_progress.xml
                         <gradient
                             android:type="linear"
-                            android:startColor="#00CF6501"
-                            android:endColor="#CF6501"
+                            android:startColor="#003C4558"
+                            android:endColor="#3C4558"
                             android:angle="0" />
```

## 为什么能修复
进度条渐变从橙色 `#CF6501`（透明→不透明）换为深蓝灰色 `#3C4558`，配合同步替换的 `icon_seekbar_bg.png` 切图，与新版导航栏视觉规范一致。纯资源替换，无逻辑风险；唯一注意点是 `drawable-mdpi` 目录直接放整图，多密度适配依赖设计切图齐全。

## 复盘与经验
- **UI 规格变更建议走资源版本化管理**：颜色值硬编码在 layer-list 里，多主题/多车型时宜收敛到 color 资源引用，避免全局搜索替换。
- 复盘统计时应把此类"缺陷"单独归类为设计迭代，避免污染真缺陷的根因分布。

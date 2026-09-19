# 无单号 · Setting 端 "CarLink" 文案改为小写 "Carlink"

- **提交**：`715ef4a4` | 2026-08-04 | dufan | Setting | feature
- **关联单**：无

## 需求/目标
与 Launcher `92d2adb0` 同批的文案规范统一：Setting 热点弹窗中互联产品名 "ICCOA CarLink" 改为 "ICCOA Carlink"。

## 实现结构
类型：纯文案提交。
单文件单行：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt`，`currentConnectType==3` 分支的返回字符串改为 `ICCOA Carlink`。

## 关键代码
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
@@ -20
                             1 -> "AppleCarPlay"
                             2 -> "HUAWEIHiCar"
-                            3 -> "ICCOA CarLink"
+                            3 -> "ICCOA Carlink"
                             else -> ""
```
实现讲解：一处改动，与 Launcher 侧两分钟内先后提交，完成跨模块同词统一。注意 Setting 此处品牌名同样是代码硬编码且各产品空格风格不一（`HUAWEIHiCar` 无空格），说明该 when 分支的展示文案本应走统一资源。

## 复盘与要点
- 跨模块同一品牌文案要一次改齐（本批分两个 commit），更稳妥的做法是把互联产品名收敛到公共模块的单一资源/常量。
- 全库 grep "CarLink" 应作为此类改名后的例行验收动作，防止测试环境/埋点等暗处残留。

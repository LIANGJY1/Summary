# 无单号 · Launcher 端 "CarLink" 文案改为小写 "Carlink"

- **提交**：`92d2adb0` | 2026-08-04 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
按品牌文案规范把 Launcher 中 ICCOA 互联的产品名由 "ICCOA CarLink" 统一改为 "ICCOA Carlink"。

## 实现结构
3 个文件各 2 行：
- `res/values/strings.xml`、`res/values-en/strings.xml`：`carlink`/`carlink_name` 两个字符串值改为 `ICCOA Carlink`
- `function/link/LinkActivity.kt`：蓝牙开启提示与 PIN 码提示两处 `String.format` 内联硬编码 "ICCOA CarLink" 同步改为 "ICCOA Carlink"
一句话：纯文案微调，无逻辑改动。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/function/link/LinkActivity.kt
@@ -181
-                    if (mIsCarLink) "ICCOA CarLink" else "HUAWEI HiCar"
+                    if (mIsCarLink) "ICCOA Carlink" else "HUAWEI HiCar"
```
实现讲解：改动同时覆盖了字符串资源与代码内联文案——这类品牌名在代码里硬编码是此前遗留，导致一次改名要动三处。

## 复盘与要点
- 品牌名/产品名必须只存在于 strings.xml，代码内联格式化参数是最容易漏改的角落（本次恰好在 LinkActivity 抓到两处）。
- 多语言 values-en 同步改，避免中英文案不一致；建议评审时用全库 grep 校验品牌词残留。

# YD-392767 · UI错误：慢充电流提示按键与UI不一致

- **提交**：`0e4a7bd0` | 2026-06-29 | liqingqing | EnergyManagement | bugfix（资源引用修复）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
慢充电流提示按钮的 info 图标显示为旧版样式，与第二版 UI 设计稿不一致。

## 根因分析
提交消息 [why] 说得明确："引用资源错误，没有正确引用资源文件（第一版ui设计稿的资源文件和第二版ui设计稿的资源文件不一样）"。以 diff 验证：`slow_charge_info_theme.xml` 是一个 `<bitmap>` 包装 drawable（日/夜两份），其 `android:src` 指向 `@drawable/slow_charge_info`——第一版设计稿切图；第二版切图 `slow_charge_info2.png` 已存在于工程外（本次随提交新增到 `drawable-mdpi/` 与 `drawable-night-mdpi/`），但两个 theme wrapper 都没有更新引用。属于典型的"切图进了仓库、引用没换"的半截交接。

## 关键代码修改
改动文件：application/EnergyManagement/src/main/res/drawable/slow_charge_info_theme.xml、application/EnergyManagement/src/main/res/drawable-night/slow_charge_info_theme.xml、res/drawable-mdpi/slow_charge_info2.png（新增，二进制）、res/drawable-night-mdpi/slow_charge_info2.png（新增，二进制）

```diff
--- application/EnergyManagement/src/main/res/drawable/slow_charge_info_theme.xml
@@ src 指向第二版切图
 <bitmap xmlns:android="http://schemas.android.com/apk/res/android"
     android:gravity="fill"
-    android:src="@drawable/slow_charge_info" />
+    android:src="@drawable/slow_charge_info2" />
```
（`drawable-night/slow_charge_info_theme.xml` 同样一行改动；两个 PNG 分别为日/夜 mdpi 版本。）

## 为什么能修复
bitmap wrapper 的 `src` 改指第二版切图后，所有引用 `slow_charge_info_theme` 的按钮立即渲染新图标；日/夜两份 PNG 通过 `drawable-mdpi`/`drawable-night-mdpi` 限定符自动分派，夜间模式不会用错图。改动 2 行 + 2 张图，无任何逻辑风险。遗留：旧图 `slow_charge_info` 未删除（可能仍被他处引用），`_theme.xml` wrapper 命名与 `drawable-night` 下同名文件的分发机制依赖资源限定符优先级，新增密度目录（如 mdpi 之外的 dpi）时需要成对补图，否则会 fallback 到默认密度图。

## 复盘与经验
- **切图交接要带"引用变更清单"**：新资源入库 ≠ 生效，UI 走查前先 grep 旧资源名的全部引用点。
- **用 bitmap wrapper 集中换图是低成本修法**：引用方不动、只改 wrapper 的 `src`，日/夜两处对称修改即可。
- **设计稿版本化的资源命名**（`slow_charge_info` vs `slow_charge_info2`）缺语义，宜带版本号目录或注明来源稿次，避免"哪张是新版"靠记忆。

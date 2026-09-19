# 无单号 · 移除无用代码资源（Launcher 瘦身）

- **提交**：`7f7ae494` | 2026-07-09 | dufan | Launcher | feature
- **关联单**：无

## 需求/目标
**提交类型：资源/死代码清理**。清理 Launcher 中来自旧车型（福田 itink）项目遗留的无用常量、未使用的 HiCar 切换弹窗、失效图片与字符串资源，共 49 个文件、+3/-697 行。

## 实现结构
主要删除项：
- `Constants.java`：删 26 行旧车型常量（`com.foton.itink.*` 包名、HiCar/萌驾/亿连接广播等，均无引用）。
- 整文件删除 `function/applist/HiCarSwitchDialogFragment.java`（244 行，含配套 layout `dialog_switch_hi_car.xml` 64 行与 selector 图片）。
- `AppInfo.java/AppListFragment.kt/AppListActivity.kt/AppInfoUtils.java`：删未被调用的方法与字段（共约 100 行）。
- 资源：删除 `app_personal_*`、`app_user_manual_*`、`home_bg*` 等 mdpi 图片与 11 个 drawable xml；`values(-en)/strings.xml` 删 128 行无效文案。
- 代码性改动仅 1 处：`NotificationUtils.java` 2 行改动（适配删除项的引用修正）。

## 关键代码
```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/Constants.java
-    public static final String MESSAGE_CENTER = "com.foton.itink.screen.modulemessage";//消息中心
-    public static final String SMART_CAR = "com.foton.itink.screen.smartcar";//智能管家
-    public static final String PERSONAL_CENTER = "com.foton.itink.screen.user";//个人中心
-    public static final String PACKAGE_DVR = "com.yadea.avmlogin.dvr";//自定义的dvr包名
```
实现讲解：典型换平台后的技术债偿还——Launcher 由旧车型项目移植而来，先整包搬入再按需裁剪。本提交按"引用计数为零"标准批量删除，包级常量、整类、资源三档同步清，避免只删代码不删资源导致的包体虚胖。

## 复盘与要点
- 可复用手势：移植项目稳定后做一轮"死代码清扫"，优先删无引用常量与整文件（风险低、收益大），配合 IDE 的 unused 声明检测与编译期 R 类校验兜底。
- 风险控制点：删公共常量前必须全局搜引用（含其他模块 reflection），本次保留 `NotificationUtils` 的引用修正说明作者做了这步。
- 遗留风险：`drawable-mdpi` 图片删除依赖运行验证各机型无回退引用（如代码里 `getIdentifier` 动态取资源），静态扫描发现不了这类引用。

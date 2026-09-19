# 无单号 · 移除无用代码（SystemUI 组件级死代码清理）

- **提交**：`df2db5c6` | 2026-07-15 | dufan | SystemUI | feature
- **关联单**：无

## 需求/目标
**提交类型：死代码清理**。删除 SystemUI 中整类无引用的旧组件（时钟系列控件、亿连广播、圆形进度条等），23 个文件、+10/-1373 行，与 `e3b8d098`（资源大扫除）互补，构成资源+代码双线瘦身。

## 实现结构
整类删除（均无引用）：
- 时钟族：`statusbar/widget/Clock.java`（238 行，旧模拟时钟）、`DigitalClockView.kt`（110）、`MinimalClockView.kt`（141）；
- 导航栏：`navbar/widget/CircleProgressBar.java`（179，圆形进度条）、`FlexBoxLayout2.java`（135）、`StatusBarWindowManager2.kt`（55，"2"后缀的旧版窗口管理）、`broadcast/EasyConnStatusReceiver.java`（36，监听 `net.easyconn.link.in/out` 亿连连接广播，旧互联方案）、`HideQuickSettingReceiver.kt`（26）及其 Manifest 注册（-9 行）；
- 工具类：`util/SystemLanguageManager.java`（91）、`ToolUtils.java`（56）、`PopDialogManager.java`（51）、`UsbFileUtils.java`（32）、`SettingUtils.kt`（39）。
- 存活性改动：`NavBarActor.kt`/`StatusBarIconController.java` 中 `ResUtil` 引用迁移至公共库 `ResourceUtils`（`getDimensionPixelSize` → `getDimension`）。

## 关键代码
```diff
--- a/application/SystemUI/src/main/AndroidManifest.xml
-        <receiver
-            android:name=".statusbar.broadcast.HideQuickSettingReceiver"
-            android:enabled="true"
-            android:exported="true">
-            <intent-filter>
-                <action android:name="com.android.systemui.hide_quick_setting" />
-            </intent-filter>
-        </receiver>
```
实现讲解：清理按"类 → Manifest 注册 → 引用方"三层推进。特别值得肯定的是 Manifest 的 `exported="true"` receiver 一并摘除——死广播若留在 Manifest，外部应用仍可拉起它，既是攻击面也是耗电点；`ResUtil → ResourceUtils` 则是借清理机会向公共库收敛重复工具。

## 复盘与要点
- 死代码判定顺序建议固定为：全局搜类名 → 查 Manifest/反射/`getIdentifier` 动态引用 → 删类与注册 → 编译验证。本提交的 `EasyConnStatusReceiver` 依赖广播 action 字符串触发，静态引用搜不到调用，必须从 Manifest 入手确认。
- 可复用手法：删除"2 后缀"类（`StatusBarWindowManager2`）前先确认现役版本，这类改名共存文件最易被误判为"新的那个没用"。
- 与 `e3b8d098` 当天先后合入同一模块，建议此类清理合并单提交或明确拆分说明（资源/代码），利于回滚粒度控制。

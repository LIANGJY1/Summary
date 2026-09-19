# YD-392973 · BTMusic 黑夜模式未连接页面图片泛白
- **提交**：`82acf087` | 2026-07-30 | hedeyuan | BTMusic | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
黑夜模式下蓝牙音乐"未连接"页面的蓝牙图标图片泛白，与 UI 稿不符。

## 根因分析
未连接图标 `ic_no_connect_bt` 只有白天版本（`drawable-mdpi`），没有 `drawable-night-mdpi` 夜间限定符资源。黑夜模式下系统回退加载白天版浅色图，深色背景上便显得泛白。另外 `MainActivity.kt` 断连分支中误把背景设到了 `clContent`（`mBinding!!.clContent.setBackgroundResource(R.drawable.bg_main)`），覆盖了本应作用于未连接视图 `clForgetContent` 的背景，加剧了显示异常。

## 关键代码修改
改动文件：`application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt`、新增 `application/BTMusic/src/main/res/drawable-night-mdpi/ic_no_connect_bt.png`（二进制图片资源）
```diff
--- application/BTMusic/src/main/java/com/yadea/btmusic/MainActivity.kt (断连分支)
-                mBinding!!.clContent.setBackgroundResource(R.drawable.bg_main)
+                //mBinding!!.clContent.setBackgroundResource(R.drawable.bg_main)
+                mBinding!!.clForgetContent.background = resources.getDrawable(R.drawable.bg_main, null)
```
新增夜间图标 `drawable-night-mdpi/ic_no_connect_bt.png`（二进制更新）。

## 为什么能修复
新增 `-night-mdpi` 限定符下的深色版蓝牙未连接图标后，黑夜模式系统自动选用夜间资源，消除泛白；背景设置目标从 `clContent` 修正为 `clForgetContent`，恢复未连接视图自身的背景层次。隐患：原 `clContent` 背景设置被注释保留，断连后 `clContent` 不再被显式刷背景，依赖 `switchTheme()` 等其他路径兜底，需回归验证主题来回切换场景。

## 复盘与经验
- "图片泛白/看不清"类夜间 bug 的第一排查点：该 drawable 是否缺少 `-night` 版本，缺省时系统静默回退白天资源。
- View 层级相近时（clContent vs clForgetContent） setBackground 极易设错目标控件，建议背景管理收敛到统一主题切换入口，避免散落在业务分支里。
- 注释掉旧代码再补新代码的做法应改为直接替换，减少噪音。

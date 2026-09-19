# SIR-6343 · 座椅位置名称含 Emoji 导致 3D 车模不显示
- **提交**：`eeb6fcf2` | 2026-08-31 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
在车辆控制里把座椅位置名称改成表情"🐒"后，3D 车模的座椅记忆标签不显示；进入修改弹窗反而能看到名字，前后表现矛盾。

## 根因分析
座椅名经 Android 输入框可自由输入 Emoji（系统字体可回退渲染），但名称最终要发给 Kanzi 3D 车模渲染，而 Kanzi 使用的 MiSans 字体不含 Emoji 字形，找不到字形即不渲染该文本，座椅记忆标签整体消失。原防御只在 `RenameDialogManager` 一处通过 `EditDialog.setInputFilter(seatNameInputFilter)` 注入过滤，且调用链上还有 Setting 侧 `VehicleControlFragment` 直接 new `EditDialog` 的路径完全没过滤；`SeatNameStore.sanitizeSeatName` 只在确认保存时清洗一次，历史存量脏名与旁路写入都会把 Emoji 带进 3D 车模。"输入侧过滤点不全 + 渲染侧字体能力不匹配"共同造成该缺陷。

## 关键代码修改
改动文件：SeatRenameDialog.kt（新增）、EditDialog.kt、RenameDialogManager.kt、VehicleControlFragment.kt
```diff
--- a/component/CommonTools/src/main/java/com/yadea/common/dialog/SeatRenameDialog.kt（新增）
+class SeatRenameDialog(...) : EditDialog(title, content, hint, dialogBg) {
+    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
+        mBinding.etContent.filters += seatNameInputFilter
+        super.onViewCreated(view, savedInstanceState)
+    }
+    companion object {
+        val seatNameInputFilter = InputFilter { source, start, end, _, _, _ ->
+            val original = source.subSequence(start, end).toString()
+            val filtered = sanitizeSeatName(original)
+            if (filtered == original) null else filtered
+        }
+        fun sanitizeSeatName(name: String?): String {
+            ...
+            val codePoint = name.codePointAt(offset)
+            offset += Character.charCount(codePoint)
+            if (!isEmojiCodePoint(codePoint)) result.appendCodePoint(codePoint)
+            ...
+        }
+        private fun isEmojiCodePoint(codePoint: Int): Boolean {
+            return (codePoint in 0x1F000..0x1FAFF)   // 主 Emoji 区
+                    || (codePoint in 0x1F3FB..0x1F3FF) // 肤色修饰符
+                    || codePoint == 0x200D             // ZWJ 连接符
+                    || codePoint == 0xFE0F ...         // 样式选择符等
+        }
+    }
+}
```
`EditDialog` 改为 `open`、`mBinding` 改为 `val` 公开、删除 `setInputFilter` 机制；`RenameDialogManager` 与 `VehicleControlFragment` 两处入口统一改用 `SeatRenameDialog`，确认回调仍保留 `SeatNameStore.sanitizeSeatName(content.trim())` 二次清洗。

## 为什么能修复
过滤点从"一个调用方的可选 filter"升级为"座椅重命名专用 Dialog 子类"：输入时 InputFilter 按 Unicode code point 拦截（含代理对、ZWJ、肤色修饰符、样式选择符），保存时再清洗一次，Setting/Launcher 两个入口都收口到同一类，Emoji 从源头无法进入座椅名，发往 Kanzi 的字符串只剩 MiSans 可渲染字符。隐患：`isEmojiCodePoint` 采用区间枚举，Emoji 标准持续扩充（Unicode 新版本新区块）可能漏网；混合字符串整段删除的策略可能误伤零宽连接符构成的合法组合。

## 复盘与经验
- 跨渲染引擎（Android→Kanzi）传字符串时，能力集合以"最弱渲染端"为准，输入侧必须按最弱端白名单过滤。
- "可选注入的 InputFilter"防不住旁路调用方：把约束内置到专用子类/入口类，比靠调用方自觉传 filter 可靠。
- Emoji 处理必须按 code point 而非 char，代理对（&#x1F412; 是两个 char）按 char 过滤会产生残缺字符。

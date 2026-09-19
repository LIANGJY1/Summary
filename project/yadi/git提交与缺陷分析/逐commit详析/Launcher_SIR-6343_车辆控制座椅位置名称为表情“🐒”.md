# SIR-6343 · 座椅名称含表情时 3D 车模座椅记忆不显示

- **提交**：`5373410a` | 2026-08-27 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
用户把车辆控制座椅位置命名为表情（如 🐒）后，3D 车模（Kanzi 渲染）的座椅记忆名称不显示；进入修改弹窗重新点确认后才显示。

## 根因分析
座椅名称从 Android 侧经数据通道下发给 Kanzi 渲染。Android 的 `EditText` 支持系统 Emoji 字体回退，输入和保存环节都能"看见"表情，校验形同虚设；但 Kanzi 侧使用 MiSans 字体渲染，MiSans **不含 Emoji 字形**，字符串传输本身正常，Kanzi 拿到文本后找不到 🐒 对应字形即无法渲染，座椅记忆名称整体不显示（缺陷库根因照录）。点"修改"后显示，是因为重命名确认路径上的某个环节（重新下发/默认名回退）产生了 Kanzi 可渲染的文本，进一步印证问题出在"进入 Kanzi 的文本内容"而非传输链路。Emoji 的 Unicode 组成（代理对、ZWJ `U+200D`、肤色修饰符、样式选择符 `U+FE0F`）使按 char 过滤必然漏删，需要按 code point 处理。

## 关键代码修改
改动文件：`application/Launcher/src/main/java/com/yadea/launcher/manager/SeatNameStore.java`、`RenameDialogManager.kt`、`control/KanziDataSourceManager.java`、`component/CommonTools/src/main/java/com/yadea/common/dialog/EditDialog.kt`

```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/manager/SeatNameStore.java
@@ -42,10 +46,39 @@ public final class SeatNameStore {
-        String name = names[position - 1];
+        String name = sanitizeSeatName(names[position - 1]);
         return name == null || name.isEmpty() ? DEFAULT_NAMES[position] : name;
     }
+
+    /** Remove emoji while preserving ordinary text, spaces and punctuation. */
+    public static String sanitizeSeatName(String name) {
+        if (name == null || name.isEmpty()) {
+            return "";
+        }
+        StringBuilder result = new StringBuilder(name.length());
+        for (int offset = 0; offset < name.length();) {
+            int codePoint = name.codePointAt(offset);
+            offset += Character.charCount(codePoint);
+            if (!isEmojiCodePoint(codePoint)) {
+                result.appendCodePoint(codePoint);
+            }
+        }
+        return result.toString();
+    }
+
+    private static boolean isEmojiCodePoint(int codePoint) {
+        return (codePoint >= 0x1F000 && codePoint <= 0x1FAFF)
+                || (codePoint >= 0x2600 && codePoint <= 0x27BF)
+                || (codePoint >= 0x2300 && codePoint <= 0x23FF)
+                || (codePoint >= 0x2B00 && codePoint <= 0x2BFF)
+                || (codePoint >= 0x1F1E6 && codePoint <= 0x1F1FF)
+                || (codePoint >= 0x1F3FB && codePoint <= 0x1F3FF)
+                || codePoint == 0x200D || codePoint == 0x20E3
+                || codePoint == 0xFE0E || codePoint == 0xFE0F;
+    }
```

```diff
--- a/application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java
@@ -740,8 +740,12 @@ public class KanziDataSourceManager implements IKanziDataListener {
     private void sendSeatNameToKanzi(int position, String seatName) {
         String key = SeatNameStore.getKanziTextKey(position);
-        kanziManager.setValue("", key, seatName);
+        String safeSeatName = SeatNameStore.sanitizeSeatName(seatName);
+        if (safeSeatName.isEmpty()) {
+            safeSeatName = SeatNameStore.getSeatName(position);
+        }
+        kanziManager.setValue("", key, safeSeatName);
```

（`RenameDialogManager` 给 `EditDialog` 挂 `InputFilter` 实时拦截输入 Emoji、confirm 时再 `sanitizeSeatName` 兜底；`SeatNameStore.saveSeatName` 保存前清洗、空串拒存；`EditDialog` 新增 `setInputFilter` 扩展点保留原 filters。）

## 为什么能修复
修复在"输入 → 保存 → 读取 → 发送 Kanzi"四个环节布防：`EditDialog` 的 `InputFilter` 在键入时实时剔除 Emoji（用户体验上根本输不进去）；`saveSeatName` 保存前清洗并拒绝纯 Emoji 名；`getSeatName` 读取历史数据时清洗（治理已存在的脏数据）；`sendSeatNameToKanzi` 发送前最后过滤，清洗后为空则回退默认名，保证 Kanzi 拿到的永远是 MiSans 可渲染的文本。`sanitizeSeatName` 按 code point 遍历并覆盖 Emoji 各 Unicode 区段（含代理对、ZWJ、肤色、样式选择符），不会把组合 Emoji 拆成半个残字。副作用：极小——合法的中文/英文/数字/空格/普通标点全部保留；风险点在于 Emoji 区段枚举是白名单式边界判断，未来 Unicode 新增 Emoji 区段需补表。

## 复盘与经验
- 跨渲染端（Android → Kanzi/自研引擎）传文本时，能力集以**最弱渲染端**为准：Android 侧能显示不代表下游能渲染，入口过滤是必做防线，而不是等下游"不显示"再查传输。
- 过滤 Emoji 必须按 Unicode code point 而非 char（Java char 是 UTF-16 代码单元），并覆盖 ZWJ/肤色/变体选择符等组合成分，否则只删一半留下乱码。
- 脏数据治理要考虑存量：历史保存的含 Emoji 名称不会因新校验消失，读取路径必须同步清洗（本例 `getSeatName` + `sendSeatNameToKanzi` 双兜底是完整示范）。
- 公共 `EditDialog` 增加 `setInputFilter` 链式扩展点并保留原 filters，是给通用组件开能力口的正确姿势，其他需要输入约束的场景可直接复用。

# VIR-997 · +86 加 11 位号码归属地显示"未知"
- **提交**：`5ffe7554` | 2026-09-14 | caohongliang | SystemUI(BTPhone 模块代码) | bugfix
- **缺陷库**：未关联缺陷库记录（单号 VIR-997 在 ids 中，无 defs 详情）

## 问题
拨打"86 + 11 位本地号码"形式（如 +86137xxxxxxxx）的电话时，号码归属地（地区）查询不到，界面显示"未知"。

## 根因分析
`application/BTPhone/src/main/java/com/yadea/btphone/location/PhoneNumberLookup.java` 的 `lookup(String phoneNumber)` 直接把原始号码交给 `lookupAlgorithm.lookup(phoneNumber)` 查询。本地归属地库只支持 7~11 位号码，而带中国国际冠码的号码形如 `+8613712345678` 共 13 位（86 前缀 + 11 位本地号），超长导致查不到，`phoneNumberInfo` 为 empty；随后兜底走的 `AreaCodeUtil.getPhoneNumberType(phoneNumber)` 传的仍是原始带冠码号码，同样查不出结果，最终归属地返回空、UI 显示"未知"。根因是查询入口没有做号码归一化，国际格式与本地库的输入约定不匹配。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/location/PhoneNumberLookup.java
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/location/PhoneNumberLookup.java
+++ b/application/BTPhone/src/main/java/com/yadea/btphone/location/PhoneNumberLookup.java
@@ -75,9 +75,13 @@
     @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
     public String lookup(String phoneNumber) {
-        Optional<PhoneNumberInfo> phoneNumberInfo = lookupAlgorithm.lookup(phoneNumber);
+        String normalizedPhoneNumber = normalizeForLookup(phoneNumber);
+        if (!java.util.Objects.equals(phoneNumber, normalizedPhoneNumber)) {
+            LogUtils.d(TAG, "normalize phone number for attribution lookup: " + normalizedPhoneNumber);
+        }
+        Optional<PhoneNumberInfo> phoneNumberInfo = lookupAlgorithm.lookup(normalizedPhoneNumber);
         if(phoneNumberInfo.isEmpty()){
-            return AreaCodeUtil.getPhoneNumberType(phoneNumber);
+            return AreaCodeUtil.getPhoneNumberType(normalizedPhoneNumber);
         }else{
@@ -88,4 +92,21 @@
     }
+
+    /**
+     * 清理号码格式，并去除中国大陆国际冠码后再查询本地归属地库。
+     */
+    static String normalizeForLookup(String phoneNumber) {
+        if (phoneNumber == null) {
+            return null;
+        }
+        String normalized = phoneNumber.replaceAll("[^+0-9]", "");
+        if (normalized.startsWith("+86")) {
+            return normalized.substring(3);
+        }
+        if (normalized.startsWith("0086")) {
+            return normalized.substring(4);
+        }
+        return normalized;
+    }
```

## 为什么能修复
新增 `normalizeForLookup`：先用 `replaceAll("[^+0-9]", "")` 剔除空格、横线等杂质，再剥掉 `+86` / `0086` 冠码，把 13 位国际格式还原成 11 位本地号，落在归属地库 7~11 位的支持区间内，主查询与 `AreaCodeUtil` 兜底两处都改用归一化结果，两条路径同时打通。副作用：仅用于归属地查询的副本号码，不改变拨打/显示用原号；`normalizeForLookup` 为 static 便于单测；若出现其他国家冠码仍查不到，但本项目面向中国市场可接受。

## 复盘与经验
- 外部数据（蓝牙来的号码、运营商上报号码）进查询前先归一化，是处理"格式差异导致查询失败"类 bug 的通用手法。
- 修复兜底路径也要同步改（`AreaCodeUtil` 入参一起换），否则只是把失败从主路径挪到兜底路径。
- 归一化函数做成 `static` 无副作用纯函数，方便对 `+86`/`0086`/带分隔符等用例直接补单测。

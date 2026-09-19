# SIR-7566 · CarPlay通话浮窗，联系人无姓名时显示两个电话号码
- **提交**：`30876240` | 2026-09-04 | ljl | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
CarPlay 投屏后通话中退出 CarPlay 界面，通话浮窗详情行在联系人无姓名时显示形如"13612345678 13612345678"的两个重复号码。

## 根因分析
根因是上游数据语义与下游展示约定的错配。互联 SDK 在 CarPlay 会话无联系人姓名时，会把电话号码本身作为 `displayName` 下发；而 `CarPlayCallWindow.java`（前一次提交 48462980 刚拆分出的）`resolveName()`/`resolveNumber()` 分别从 `info.getDisplayName()` 与 `info.getRemoteId()` 取值，交给 `SmartEllipsizeTextView.setNameAndNumber(name, number)` 按"姓名 + 空格 + 号码"拼接。当 displayName 就是号码时，name 与 number 内容相同，展示为"号码 号码"；若 displayName 无效，`resolveName` 返回空串，`setNameAndNumber` 退化为只显示号码，缺少"未知"兜底。修复在 `resolveName` 内加判重：新增 `isSameNumber(name, number)`，用 `digitsOnly()`（`replaceAll("\\D", "")` 提取纯数字）比较两者，避免"带空格/横线的号码格式差异"导致漏判；判同或姓名无效时不再返回空串而返回"未知"，使详情行显示"未知 136xxxx"。布局 `carplay_call_card.xml` 仅同步注释。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java；application/BTPhone/src/main/res/layout/carplay_call_card.xml
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/carplay/CarPlayCallWindow.java
             if (info != null && info.isDisplayNameValid() && !TextUtils.isEmpty(info.getDisplayName())) {
-                return info.getDisplayName();
+                String name = info.getDisplayName();
+                if (!isSameNumber(name, resolveNumber(info))) {
+                    return name;
+                }
+                LogUtils.d(TAG, "displayName is the number itself, treat as no-name: " + name);
             }
         } catch (Throwable t) {
             LogUtils.e(TAG, "resolveName failed: " + t);
         }
-        return "";
+        return "未知";
+    }
+
+    private static boolean isSameNumber(String name, String number) {
+        if (TextUtils.isEmpty(number)) return false;
+        return digitsOnly(name).equals(digitsOnly(number));
+    }
+
+    private static String digitsOnly(String s) {
+        return s == null ? "" : s.replaceAll("\\D", "");
     }
```

## 为什么能修复
把"SDK 以号码充姓名"这一上游数据形态在展示层归一化：姓名位与号码位纯数字判同后，无姓名场景收敛为统一的"未知 + 号码"展示，从根上消除重复号码；"未知"兜底也覆盖了姓名字段完全无效的情况，展示语义更完整。副作用小：若联系人姓名真的就是纯数字（极少数），会被误判为号码而显示"未知"，属可接受的极端 case；`resolveNumber` 在 `resolveName` 中被重复调用一次，开销可忽略。

## 复盘与经验
- 对接协议/SDK 下发的字段要警惕"语义复用"：无姓名时用号码顶替 displayName 是常见做法，展示层必须做判重归一化，不能信任字段名。
- 比较"可能是同一号码的不同格式"（空格、横线、国家码）时，先做 `replaceAll("\\D", "")` 的纯数字归一再 equals，比直接 TextUtils.equals 稳健。
- 展示兜底文案（"未知"）应在数据解析层统一给出，而不是留给每个 UI 控件自行处理空串，否则同类页面会出现不一致。

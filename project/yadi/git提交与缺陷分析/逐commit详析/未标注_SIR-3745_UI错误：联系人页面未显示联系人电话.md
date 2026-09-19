# SIR-3745 · 联系人页面未显示联系人电话
- **提交**：`1b4f7167` | 2026-07-30 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙电话联系人列表每一项只显示姓名，不显示电话号码，与变更后的 UI 需求不符。

## 根因分析
缺陷库标注根因为"需求变更，适配新需求及 UI 界面"：新版 UI 要求联系人 item 上同时展示号码。原实现 `ContactsAdapter.ContactViewHolder` 只持有 `tv_name` 并 bind 姓名字段，布局 `item_contacts.xml` 中没有号码控件，`ContactData.getPhoneList()` 中的号码数据白白存在但从未展示。另缺少一个"带 +86 前缀"的号码格式化工具，直接展示原始号码不符合 UI 对区号的显示要求。

## 关键代码修改
改动文件：`adapter/ContactsAdapter.java`、`adapter/CallLogAdapter.java`、`fragment/ContactsFragment.java`、`telecom/telecom/TelecomUtils.java`、`res/layout/item_contacts.xml`、`res/layout/item_call_log.xml`、`res/drawable/progress_sync_style.xml`、`res/drawable/rv_scroll_thumb.xml`（均在 application/BTPhone 下）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/adapter/ContactsAdapter.java (ContactViewHolder)
         private final TextView tvName;
+        private final TextView tvNum;
         ...
+        tvNum = itemView.findViewById(R.id.tv_num);
         ...
+            if (item.getPhoneList() != null && phoneIndex < item.getPhoneList().size()) {
+                String number = item.getPhoneList().get(phoneIndex).getNumber();
+                if (!StringUtil.isBlank(number)) {
+                    String formatNumber = TelecomUtils.getFormattedNumberWithPlus(number);
+                    tvNum.setText(formatNumber);
+                    tvNum.setVisibility(View.VISIBLE);
+                } else {
+                    tvNum.setVisibility(View.GONE);
+                }
+            } else {
+                tvNum.setVisibility(View.GONE);
+            }
--- application/BTPhone/src/main/java/com/yadea/btphone/telecom/telecom/TelecomUtils.java
+    public static String getFormattedNumberWithPlus(String number) {
+        String countryIso="CN";
+        String e164 = PhoneNumberUtils.formatNumberToE164(number, countryIso);
+        String formattedNumber = PhoneNumberUtils.formatNumber(number, e164, countryIso);
+        formattedNumber = TextUtils.isEmpty(formattedNumber) ? number : formattedNumber;
+        // UI要求区号前面带+号：当原始号码以国家代码开头(如86)但不带+时，补充+号
+        if (!formattedNumber.startsWith("+") && number.startsWith("86")) {
+            formattedNumber = "+" + formattedNumber;
+        }
+        return formattedNumber;
+    }
```
布局 `item_contacts.xml` 同步新增 `tv_num` 控件（二进制无关）。

## 为什么能修复
ViewHolder 补上 `tv_num` 引用并在 `bind()` 里按 `phoneIndex` 从 `getPhoneList()` 取号、空值 GONE、非空 VISIBLE，布局新增对应控件后号码即随列表展示；`getFormattedNumberWithPlus()` 统一用 `PhoneNumberUtils.formatNumber` 按 CN 规则格式化并补 "+"，显示形态符合 UI。隐患：`countryIso` 硬编码 "CN"，海外号码/漫游场景格式化可能不符合当地习惯；`number.startsWith("86")` 的补 + 判断对以 86 开头的本地号码存在误判可能。

## 复盘经验
- 数据模型里已有（getPhoneList）但 UI 未展示的字段，需求一变就要打通"数据→ViewHolder→布局"三层，评估需求时先查数据可得性。
- 电话号码展示必须经过统一格式化入口，散写 `setText(number)` 会在区号/分机上反复返工；但格式化的 countryIso 不应硬编码国家。
- 需求变更类"缺陷"应在缺陷库标注清楚，避免按技术缺陷统计根因时失真。

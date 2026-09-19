# SIR-2858 · HUD 蓝牙电话信息显示完善（人名截取与视图装配收口）
- **提交**：`a0c9abc9` | 2026-07-21 | hedeyuan | BTPhone | bugfix（SIR-2858 第三连修）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
HUD 上蓝牙电话显示进一步打磨：人名显示原始全名（含空格分隔的姓氏等）不适合 HUD 窄条幅；同时去电路径的 HUD 视图装配时序不稳，抽出统一入口。

## 根因分析
本提交属 SIR-2858 链路的收尾优化，diff 三类改动：① `inflateHudLayoutInto` 中 `tvUser` 显示逻辑原为"联系人名非空则显示全名"，改为取 `primaryCall.getContactName().split(" ")[0]`（首个单词），为空回退显示号码；② `tvUser` 从方法内局部变量提升为成员变量（原来 `ivHangup2`/`tvTime` 等是成员而 `tvUser` 不是），供后续状态刷新（时长/名字变化）时统一访问；③ 去电小窗口路径不再就地 new FrameLayout 装配 HUD，来电/去电统一走新抽取的 `inflateHudView()`，装配参数由 `checkIfIncomingCall()` 动态判断，消除两条路径参数写反/漏配的隐患（原去电调用 `inflateHudLayoutInto(mHudCompanionView, isHandUp, false)` 与来电的 `(false, true)` 参数语义混杂）。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java`、`floatview/FloatWindowManager.java`、`res/layout/float_hud_window.xml`

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/floatview/FloatCallWindow.java
+    private void inflateHudView() {
+        // 2. HUD屏（Display 4）：创建简单FrameLayout，inflate HUD布局
+        mHudCompanionView = new FrameLayout(mContext);
+        inflateHudLayoutInto(mHudCompanionView, true, checkIfIncomingCall());
+        mFloatWindowManager.addHudCompanionView(mHudCompanionView);
+    }
@@ tvUser 显示
-        if (!TextUtils.isEmpty(displayName)) {
-            tvUser.setText(displayName);
+        if (!TextUtils.isEmpty(primaryCall.getContactName())) {
+            if (primaryCall.getContactName().split(" ")[0].length() > 0) {
+                tvUser.setText(primaryCall.getContactName().split(" ")[0]);
+            } else {
+                tvUser.setText(primaryCall.getNumber());
+            }
         }
```

## 为什么能修复
人名显示问题由"取 contactName 首词、异常回退号码"直接解决；HUD 装配收口到单一 `inflateHudView()` 后，来电/去电共用同一套 Display 4 投屏代码，参数以运行时通话状态推导，杜绝了两条路径各自维护导致的显示遗漏。`FloatWindowManager` 少量调整与 `float_hud_window.xml` 布局微调配合显示效果。隐患：`split(" ")[0]` 对以空格开头的名字会得到空串（已用 length>0 兜底回退号码），对中文"姓 名"同样按首词截断，属于产品取舍而非通用方案。

## 复盘与经验
- 同一视图的两条装配路径（来电/去电）出现参数含义漂移时，尽早抽公共函数并用运行时状态推导参数，比各写各的更不易错。
- 局部控件引用若在后续状态回调中还要更新，必须提升为成员变量——原代码 `tvUser` 局部化导致 HUD 上人名/时长刷新只能整层重建。
- 显示名截取要定义清楚回退链（全名 → 首词 → 号码）并对空串设防，联系人姓名是不可信输入。

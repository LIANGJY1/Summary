# SIR-6264 · 点击联系人索引Z后索引跳回Y

- **提交**：`23a6fd1e` | 2026-08-23 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
蓝牙联系人已同步，点击右侧索引条字母 Z 后，列表跳到 Z 分组，但索引条高亮随即跳回字母 Y。

## 根因分析
`ContactIndexBar`（`com.yadea.btphone.view`）存在两条状态来源：用户触摸选择的 `currentIndex`，和列表滚动联动调用 `setHighlightLetter(letter)` 设置的 `highlightIndex`。缺陷库根因"索引选中和列表第一条可见联系人联动"：点击 Z 后列表执行 `scrollToPosition`，但 Z 是最后一个分组、其联系人不足以把 Z 顶到列表首位（无法顶对齐），滚动停止后"第一条可见联系人"仍是 Y 组，联动逻辑随即以可见首项反推字母调用 `setHighlightLetter("Y")`，把用户刚选中的 Z 覆盖掉。代码层面有两处缺陷：① `ACTION_UP` 时 `currentIndex` 被重置为 -1，触摸结束即丢失用户选择；② `setHighlightLetter` 在触摸期间/结束后无差别接受列表联动写入。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/view/ContactIndexBar.java（+10/-5）
```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/view/ContactIndexBar.java
@@ ACTION_UP / ACTION_CANCEL
             case MotionEvent.ACTION_UP:
             case MotionEvent.ACTION_CANCEL:
-                // 松开手指后，取消选中状态，恢复高亮状态
+                // 正常松手后保留用户最后选择的字母，避免列表无法顶对齐时回跳到上一分组
+                if (event.getAction() == MotionEvent.ACTION_UP && currentIndex >= 0) {
+                    highlightIndex = currentIndex;
+                }
                 isTouching = false;
                 currentIndex = -1;
                 invalidate();
@@ setHighlightLetter
     public void setHighlightLetter(String letter) {
+        // 操作索引条时以用户选择为准，列表滚动联动不能覆盖当前选择
+        if (isTouching) {
+            return;
+        }
+
         if (letter == null) {
             highlightIndex = -1;
         } else {
             // 在显示列表中查找字母对应的索引
             highlightIndex = displayLetters.indexOf(letter);
         }
-        // 只在非触摸状态下才重绘
-        if (!isTouching) {
-            invalidate();
-        }
+        invalidate();
     }
```

## 为什么能修复
两处改动共同确立"用户主动选择优先于滚动联动"：松手瞬间把 `currentIndex` 固化进 `highlightIndex`，用户选择不再随触摸结束丢失；触摸期间 `setHighlightLetter` 直接短路，联动回调无法覆盖选择。即使列表因数据不足无法把 Z 顶到首位，索引高亮也稳定停留在 Z。副作用：触摸期间用户手动滚动列表时索引高亮暂不跟随（松手后恢复联动），属可接受的交互取舍。

## 复盘与经验
- 同一 UI 状态有两个写入方（用户输入 vs 数据联动）时，必须定义优先级并互斥，否则必现"状态跳变"。
- "列表滚动到分组首项"在末尾分组天然无法顶对齐，凡是"可见首项反推选中项"的联动都要考虑这个边界。
- 触摸交互控件在 `ACTION_UP` 清状态时要先思考该状态是否还有价值，必要时先固化再重置。

# YD-393037 · 黑夜模式通话中来电来电浮窗未显示"来电"

- **提交**：`4d7165fd` | 2026-08-04 | liujinfeng | BTPhone | bugfix
- **缺陷库**：未关联单号（标题含 YD-393037）

## 问题
通话中再有第三方来电时，来电浮窗（三方来电布局）只显示号码，未显示"来电"状态文案。

## 根因分析
三方来电浮窗布局 `float_three_way_incoming_window.xml` 中第二路通话区域（`secondCallLayout`）只有 `tv_second_num`（号码）与接听/拒接按钮，缺少状态 TextView。通话中的新来电走该布局时没有文案位可绑定 `@string/incoming_call`，无论昼夜模式都显示不出"来电"，黑夜模式下视觉对比更明显故被报出（缺陷库未关联，提交标注"ui 缺失，补充 ui"）。

## 关键代码修改
改动文件：application/BTPhone/src/main/res/layout/float_three_way_incoming_window.xml
```diff
// application/BTPhone/src/main/res/layout/float_three_way_incoming_window.xml
         app:layout_constraintTop_toBottomOf="@id/firstCallLayout"
         app:layout_constraintStart_toStartOf="parent">
+
+        <TextView
+            android:id="@+id/tv_second_call_time"
+            android:layout_width="wrap_content"
+            android:layout_height="wrap_content"
+            android:layout_marginStart="24dp"
+            android:layout_marginTop="@dimen/dp_23"
+            android:layout_marginEnd="24dp"
+            android:gravity="center_vertical"
+            android:text="@string/incoming_call"
+            android:textColor="@color/text_default_press"
+            android:textSize="16sp"
+            app:layout_constraintTop_toTopOf="parent"
+            app:layout_constraintEnd_toEndOf="parent"
+            />
         <com.yadea.btphone.view.SmartEllipsizeTextView
             android:id="@+id/tv_second_num"
```

## 为什么能修复
在第二路通话区域内新增 `tv_second_call_time` TextView（复用时间文案控件的 id 槽位），默认文本 `@string/incoming_call`，颜色用主题色 `text_default_press`（昼夜模式自动切换），浮窗渲染时即显示"来电"。纯布局补充，逻辑零改动；隐患仅在于若代码后续向该 id 回写计时文本，需在拒接/接听分支清掉默认文案。

## 复盘与经验
- 新版浮窗布局从旧版迁移时容易漏掉状态文案控件，"号码 + 按钮"中间的状态行是高频遗漏点，UI 走查应按信息架构逐项核对。
- 文案控件默认值直接写 `@string/incoming_call` 比依赖代码 set 更稳，避免绑定时序导致的文案缺失。
- 颜色一律引用主题色资源（text_default_press）而非硬编码，昼夜模式才能自动适配。

# SIR-4082 · 黑夜模式点击控制中心"屏幕自动亮度"按钮变白

- **提交**：`603a8305` | 2026-07-28 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
黑夜模式下点击控制中心的"屏幕自动亮度"快捷按钮，按钮按压瞬间整体变白，与暗色主题严重不符。

## 根因分析
快捷按钮背景由 selector drawable 控制，按压态 `state_pressed` 的 `solid` 颜色写死为 `#CCFFFFFF`（80% 不透明度的白色）。该色值是按亮色卡片上的按压蒙层设计的，在黑夜模式下按下时，一层接近纯白的半透明蒙层盖在深色按钮上，视觉上就是"按钮变白"。缺陷库根因"按压状态颜色值错误"、方案"修改按压状态颜色值"与此完全对应。同时被删除的 `quick_button_selector_off.xml` 也含同样的 `#CCFFFFFF` 按压色，属同一问题的另一处载体。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/notification/CarNotificationListener.java；application/SystemUI/src/main/java/com/android/systemui/notification/PreprocessingManager.java；application/SystemUI/src/main/res/drawable/quick_button_selector_off.xml（删除）；application/SystemUI/src/main/res/drawable/quick_button_selector_on.xml

```diff
--- application/SystemUI/src/main/res/drawable/quick_button_selector_on.xml
     <!-- 按压状态 -->
     <item android:state_pressed="true">
         <shape android:shape="rectangle">
-            <solid android:color="#CCFFFFFF"/>
+            <solid android:color="@color/bg_button_press"/>
             <corners android:radius="12dp"/>
         </shape>
     </item>
```

```diff
--- application/SystemUI/src/main/res/drawable/quick_button_selector_off.xml（整文件删除，原内容含）
-    <item android:state_pressed="true">
-        <shape android:shape="rectangle">
-            <solid android:color="#CCFFFFFF"/>
-            <corners android:radius="8dp"/>
-        </shape>
-    </item>
```

**注意**：本提交还捎带了与单号无关的通知过滤改动——`CarNotificationListener.onNotificationPosted` 前置调用 `mPreprocessingManager.shouldFilter()`，`PreprocessingManager.isSystemNotification` 的过滤包名黑名单从 `android`/`com.android.systemui` 扩充了 `com.android.server.telecom`、`com.android.car.dialer` 两个包。与缺陷库元数据（改按压色）不符，按 diff 实际内容如实记录，该部分应属另一问题的改动混入了本提交。

## 为什么能修复
按压色由写死的近白色 `#CCFFFFFF` 换为主题色引用 `@color/bg_button_press`（该色值定义在共享主题资源库中，不在本仓库源码内，深浅模式可分别取值），按压蒙层随主题自适应，黑夜模式下不再出现白块；删除同样带错误按压色的 `quick_button_selector_off.xml` 消除了第二处载体。风险点：`bg_button_press` 依赖外部资源库提供，若目标编译环境缺该资源会编译失败（能合入说明资源库已同步）。

## 复盘与经验
- **selector 中写死带透明度的颜色是主题适配重灾区**：按压蒙层、涟漪等状态色应一律走主题属性引用（`@color`/`?attr`），让 values-night 决定实际值，白天黑夜共用一份 drawable。
- **#CCFFFFFF 这类"亮色卡片按压蒙层"色值复制到暗色场景必翻车**，评审时可把"带 alpha 的白色写死在 drawable 里"列为告警模式。
- **一个提交只做一件事**：本提交混入了通知过滤逻辑，事后按单号回溯（正如本复盘）时会造成 diff 与缺陷描述对不上的困扰；混提交会增加回归测试范围的误判（本单测试范围只写了"观察颜色变化"，通知过滤改动实际未被该单验证）。

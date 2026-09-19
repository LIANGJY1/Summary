# SIR-7438 · CarPlay 连接时，偶发蓝牙连接界面闪退
- **提交**：`665b7e3d` | 2026-09-04 | dufan | Setting（Hardwarelibs 组件） | bugfix
- **缺陷库**：等级 C · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
CarPlay 连接过程中，偶发蓝牙连接界面闪退（NullPointerException）。

## 根因分析
`BluetoothAvrcpAdapter.connect()/disconnect()` 通过反射调用 AVRCP controller 的隐藏方法：`ReflectMethodUtils.invokeMethodWithParam(...)` 的返回值被直接强转 `(Boolean)`。反射调用在目标方法不存在、权限不足或服务未就绪时返回 `null`（或触发可捕获的异常路径返回空），强转 `null` 为包装类型本身不抛错，但随后作为 `boolean` 参与运算/自动拆箱即抛 NPE。CarPlay 连接会引发蓝牙状态/连接竞争，恰好放大了反射返回 null 的概率窗口——缺陷库"空指针异常"与代码一致。注意修复点在公共组件 `Hardwarelibs`，闪退却表现在 Setting 的蓝牙界面，属于底层组件抛异常、上层页面背锅的典型链路。

## 关键代码修改
改动文件：`component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/android/BluetoothAvrcpAdapter.java`

```diff
--- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/bluetooth/android/BluetoothAvrcpAdapter.java
     public static boolean connect(@NonNull BluetoothAvrcpController btHeadset, BluetoothDevice device) {
-        return (Boolean)ReflectMethodUtils.invokeMethodWithParam(btHeadset, "connect", false, true, new Class[]{BluetoothDevice.class}, new Object[]{device});
+        Boolean result = ReflectMethodUtils.invokeMethodWithParam(
+                btHeadset, "connect", false, true,
+                new Class[]{BluetoothDevice.class}, new Object[]{device});
+        return result != null && result;
     }
 
     public static boolean disconnect(@NonNull BluetoothAvrcpController btHeadset, BluetoothDevice device) {
-        return (Boolean)ReflectMethodUtils.invokeMethodWithParam(btHeadset, "disconnect", false, true, new Class[]{BluetoothDevice.class}, new Object[]{device});
+        Boolean result = (Boolean) ReflectMethodUtils.invokeMethodWithParam(
+                btHeadset, "disconnect", false, true,
+                new Class[]{BluetoothDevice.class}, new Object[]{device});
+        return result != null && result;
     }
```

## 为什么能修复
强转改为"先接包装类型、判空再拆箱"：反射返回 `null` 时安全回落为 `false`（连接/断开视为未成功），不再抛 NPE，蓝牙界面不再闪退；调用方拿到 `false` 后走既有的失败分支。副作用是"失败"与"反射不可用"两种语义被合并为 false，若调用方需要区分需另行埋点。同类强转若组件内还有其他 `invokeMethodWithParam` 直接强转处，仍存在相同风险。

## 复盘与经验
- 反射调用的返回值永远按可空处理，`(Boolean)` 强转后直接拆箱是 NPE 高发模板；安全写法 `Boolean r = ...; return r != null && r;` 应成为团队规范。
- "偶现闪退"先看崩溃栈定位到组件边界：底层组件（Hardwarelibs）的异常往往在特定竞态（CarPlay 与蓝牙同时建立连接）下触发，UI 现象与根因常隔着一到两层。
- 涉及隐藏 API 的反射调用，还要考虑不同系统版本方法签名/存在性差异，判空 + 默认失败值是最低限度的防御。

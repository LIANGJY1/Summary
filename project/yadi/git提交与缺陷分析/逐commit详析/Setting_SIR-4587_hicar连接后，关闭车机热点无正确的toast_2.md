# SIR-4587 · HiCar 连接后关闭车机热点无 toast 提示、无二次确认
- **提交**：`5bc2ae47` | 2026-08-03 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
HiCar 已连接时在设置里关闭车机热点，既没有正确的提示文案，也没有二次确认弹窗，直接断开。

## 根因分析
`HotspotDialogFragment` 热点开关的关闭分支里，二次确认弹窗（`TextDialog`）的触发条件是 `DeviceConnectManager.getInstance().getCurrentConnectType() == 2`——只覆盖 HiCar（类型 2）一种互联方式，且提示文案是写死的 `R.string.hot_point_close_hint`（内容还是"关闭蓝牙连接会影响 HUAWEIHiCar 通话…关闭蓝牙?"，文案语义都对不上：明明在关热点，提示说的却是关蓝牙）。CarPlay（1）、ICCOA CarLink（3）等其它连接类型完全不弹确认；缺陷库结论一致："未添加对应逻辑，添加对应逻辑"。

## 关键代码修改
改动文件：`application/Setting/.../diologfragment/HotspotDialogFragment.kt`、`application/Setting/src/main/res/values/strings.xml`、`application/Setting/src/main/res/values-en/strings.xml`
```diff
--- application/Setting/.../diologfragment/HotspotDialogFragment.kt
-                if (DeviceConnectManager.getInstance().getCurrentConnectType() == 2) {
+                val currentConnectType = DeviceConnectManager.getInstance().getCurrentConnectType()
+                if (currentConnectType == 1 || currentConnectType == 2 || currentConnectType == 3) {
+                    fun getHint(): String {
+                        return when (currentConnectType) {
+                            1 -> "AppleCarPlay"
+                            2 -> "HUAWEIHiCar"
+                            3 -> "ICCOA CarLink"
+                            else -> ""
+                        }
+                    }
                     TextDialog(
                         "",
-                        ResourceUtils.getString(R.string.hot_point_close_hint),
+                        String.format(getString(R.string.hot_point_close_hint), getHint()),
...
--- application/Setting/src/main/res/values/strings.xml
-    <string name="hot_point_close_hint">关闭蓝牙连接会影响HUAWEIHiCar通话,是否确定\n关闭蓝牙?</string>
+    <string name="hot_point_close_hint">关闭车机热点将会断开%s连接,确定\n要关闭热点?</string>
```

## 为什么能修复
确认弹窗的触发条件从"仅 HiCar（==2）"扩展为"CarPlay(1)/HiCar(2)/ICCOA(3) 任一互联连接"，连接中关热点必弹二次确认；文案改为 `%s` 占位模板并用 `String.format` 按连接类型注入 "AppleCarPlay/HUAWEIHiCar/ICCOA CarLink"，提示内容准确。隐患：① 连接类型数字（1/2/3）是魔法值，未引用常量，后续新增互联协议（如 CarLink++ / 其他）容易再漏；② `values-en` 中仍是中文文案，英文环境未国际化；③ "无连接时无提示直接关"的路径保持原行为，符合预期。

## 复盘经验
- 白名单/条件枚举（连接类型）扩展新成员时，全局搜索 `== 2` 这类字面量比较点逐一适配；更优做法是把"连接名称"封装进 `DeviceConnectManager` 返回，而不是在 UI 层 when 魔法值。
- 提示文案涉及动作对象时（关热点≠关蓝牙）必须核对文案与实际行为的一致性，错误文案比没有文案更伤信任。
- 带 `%s` 占位的字符串模板是处理"同类提示多主体"的标准做法，新增互联类型时只改一处 when。

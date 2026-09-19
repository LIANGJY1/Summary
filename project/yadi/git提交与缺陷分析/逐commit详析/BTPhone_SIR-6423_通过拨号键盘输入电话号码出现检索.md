# SIR-6423 · 拨号盘检索状态下切到收藏/最近通话搜索结果为空

- **提交**：`5d18b566` | 2026-08-25 | liujinfeng | BTPhone | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 蓝牙电话

## 问题
在拨号键盘输入号码出现检索结果后，从联系人页切换到收藏、最近通话页，两个页签的检索结果均不显示。

## 根因分析
`MainActivity` 中拨号数字由 `StringBuilder dialedNumber` 保存原始按键序列，而 `binding.dialedNumberText` 是展示控件，经 `updateDialedNumberView()` 用 `StringUtil.formatPhoneSpaces(numberStr)` 做过"加空格"的格式化（如 `138 0013 8000`）。页签切换处的 `onSearch(position, ...)` 却把 `binding.dialedNumberText.getText().toString()`（格式化后带空格的显示串）当作搜索条件传下去。收藏/最近通话数据按连续数字匹配，带空格的查询串自然匹配不到任何记录；联系人页可能因匹配逻辑不同（如对 query 做了去空格处理）而表现正常，形成"切页签就不显示"的差异性现象。

## 关键代码修改
改动文件：`application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java`

```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/MainActivity.java
@@ -881,7 +881,8 @@ public class MainActivity extends AppCompatActivity implements LapseTouchLayout.
 //            // 隐藏通讯录工具栏
         }
-        onSearch(position, binding.dialedNumberText.getText().toString());
+        // 确保搜索字符串无空格
+        onSearch(position, dialedNumber.toString());
     }
```

## 为什么能修复
改用原始数据源 `dialedNumber.toString()` 后，无论 `dialedNumberText` 当前显示格式如何，搜索条件永远是用户实际键入的连续数字串，三个页签拿到一致的 query，收藏与最近通话即可正常检索。修复思路是"展示值与数据值分离"：显示层可以做任意格式化，业务逻辑必须读原始值。缺陷库中还提到"待验证版本号填错了"，但本 diff 只含搜索条件修改，版本号修正应在其他提交中处理（以 diff 实际为准）。

## 复盘与经验
- "显示文本"不可直接当"业务数据"用：任何格式化（加空格、加分隔符、货币符号）后的 UI 文本再喂回逻辑层，都会埋雷。取值应回到源头字段（这里是 `dialedNumber`）。
- 多页签共用一个搜索入口时，query 来源应统一收敛到一个方法/字段，避免各处各取各的，出现"联系人页正常、其他页异常"这种难排查的差异性 bug。
- 回归测试要覆盖"状态保持"场景（检索态下切页签），而不是只在单页签内验证搜索。

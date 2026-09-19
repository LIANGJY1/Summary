# 无单号 · 蓝牙音乐打包错误修复（实为缺陷修复，标题误标 feature）

- **提交**：`f3f3d60d` | 2026-06-26 | dufan | BTMusic | feature（实为 bugfix）
- **关联单**：无

> 定性说明：标题标注 feature，但 diff 内容是修复源码中被写入的乱码字符导致编译失败的问题，属缺陷修复，按 bugfix 模板撰写。

## 问题
蓝牙音乐模块打包（编译）失败：`BluetoothController.kt` 源文件中混入了非法字符 `[]` 与被切断的标识符，代码无法通过编译。

## 根因分析
两处源码损坏，位置均在字符串/标识符内部：
1. L183 附近：`getConnectionStateString(state)` 的收尾括号处被插入了 `[]` 与多余方括号（`)\n    []               )[]`）；
2. L217 附近：赋值语句 `foundConnected = true` 被写成 `foun [dConnected = true`，变量名中间被插入 `[`。

成因大概率是编辑器/合并/传输过程对文件做了破坏性写入（非正常代码变更）。同时 `.gitignore` 只忽略 `/.gradle`、`/.idea`，未忽略各模块 `build/` 目录，也是仓库被构建产物污染的根因之一。

## 关键代码修改
```diff
# application/BTMusic/src/main/java/com/yadea/btmusic/manager/BluetoothController.kt
-                        "a2dp device status check: " + device.name + " - " + getConnectionStateString(
-                            state
-                        )
-    []               )[]
+                        "a2dp device status check: " + device.name + " - " + getConnectionStateString(
+                            state
+                        ))
...
-                    foun [dConnected = true
+                    foundConnected = true
```
```diff
# .gitignore
-/.gradle
+.gradle/
+.idea/
+*.iml
+local.properties
+.DS_Store
+/build
+/captures
+.externalNativeBuild
+.cxx
+application/*/build/
```

## 为什么能修复
删除插入在表达式中的非法 token 后，语法恢复合法、`foundConnected` 标识符恢复可解析，编译通过；`.gitignore` 补齐 `application/*/build/` 等规则后，构建产物不再进入版本库，从源头消除此类"打包错误"再次提交的可能。

## 复盘与经验
- "打包错误修复"类提交要检查 diff 是否只是机械性字符损伤——若是，说明代码在提交前缺少最低限度的编译自检，CI 上加一道 compile 门禁即可拦截。
- `.gitignore` 不完善会让 build 产物混入提交（本批次 54d746cf 等提交就携带了大量 build/ 产物），本提交同步补齐 ignore 规则是正确的配套动作。

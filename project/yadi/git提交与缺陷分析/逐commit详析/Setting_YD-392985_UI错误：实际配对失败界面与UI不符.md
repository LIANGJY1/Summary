# YD-392985 · Setting 配对失败界面与 UI 不符
- **提交**：`1f907348` | 2026-07-30 | dufan | Setting | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
蓝牙配对失败弹窗界面（PairDialogActivity 的提示态）标题显示的是设备名，与 UI 稿要求的固定标题"配对失败"不符。

## 根因分析
`PairDialogActivity.setHintView()` 切换到失败提示视图（`llPair` 隐藏、`llHint` 可见）时，用代码动态覆写标题：`mBinding.tvTitle.text = device?.name ?: deviceName`。而布局 `activity_require_pair.xml` 中的 `tvTitle` 本身没有默认文案。两个因素叠加：一是运行时把动态设备名写进了设计稿中应为固定文案的标题位；二是布局无兜底文本，导致界面与 UI 稿不一致。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt`、`application/Setting/src/main/res/layout/activity_require_pair.xml`、`application/Setting/src/main/res/values/strings.xml`、`application/Setting/src/main/res/values-en/strings.xml`
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/activity/PairDialogActivity.kt
-        mBinding.tvTitle.text = device?.name ?: deviceName
+//        mBinding.tvTitle.text = device?.name ?: deviceName
--- application/Setting/src/main/res/layout/activity_require_pair.xml
                     android:gravity="center_horizontal"
+                    android:text="@string/pair_fail"
                     android:textColor="@color/text_default_default"
--- application/Setting/src/main/res/values/strings.xml
+    <string name="pair_fail">配对失败</string>
```

## 为什么能修复
取消运行时对 `tvTitle` 的动态覆写，并在布局上直接声明默认文案 `@string/pair_fail`（中英文资源同步新增），界面进入失败态即固定显示"配对失败"，与 UI 稿一致。隐患：代码中以注释而非删除的方式保留旧逻辑，`values-en` 中也写的是中文"配对失败"，英文环境下标题仍为中文，存在国际化遗漏。

## 复盘与经验
- 弹窗标题这类"固定文案"不应由代码动态赋值覆盖；文案应内聚在布局/字符串资源里，代码只处理真正动态的内容。
- 改 UI 文案时必须同步补全多语言资源（本例 values-en 漏翻），评审时要把 `values-*` 目录纳入 checklist。
- 用注释禁用代码是临时手段，确认稳定后应删除，避免死代码误导后续维护。

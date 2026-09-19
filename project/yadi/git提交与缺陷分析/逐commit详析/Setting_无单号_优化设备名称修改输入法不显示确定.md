# 无单号（SIR-XXX）· 设备名称修改输入法不显示"确定"键
- **提交**：`14f99caa` | 2026-09-08 | sgh | Setting | bugfix（优化类，未关联正式单号）
- **缺陷库**：未关联单号（提交 why 记为"需求遗漏"）

## 问题
修改设备名称的编辑弹窗中，软键盘不显示"确定"（Done）按钮，用户输入完无法便捷确认。

## 根因分析
`component/CommonTools` 的公共编辑弹窗布局 `dialog_edit.xml` 中，输入框 EditText 的 `imeOptions` 只配了 `flagNoExtractUi|flagNoFullscreen`，没有声明动作键；且未设置 `android:inputType`。Android 输入法只有在 EditText 的输入类型（inputType）为可编辑文本且 imeOptions 指定了动作（actionDone/actionSearch 等）时，才会在键盘右下角渲染"确定/Done"动作键——缺省 inputType 加上未指定 action，部分输入法直接不显示动作键。补上 `android:inputType="text"` 与 `imeOptions` 追加 `actionDone` 即可让键盘出现确定键。

## 关键代码修改
改动文件：component/CommonTools/src/main/res/layout/dialog_edit.xml
```diff
--- component/CommonTools/src/main/res/layout/dialog_edit.xml
@@ -39,8 +39,9 @@
             android:background="@null"
             android:gravity="center_vertical"
-            android:imeOptions="flagNoExtractUi|flagNoFullscreen"
+            android:imeOptions="flagNoExtractUi|flagNoFullscreen|actionDone"
             android:textColor="@color/text_default_default"
+            android:inputType="text"
             android:maxLength="6"
```

## 为什么能修复
`inputType="text"` 声明了标准文本输入能力，`actionDone` 明确请求键盘右下角渲染"确定"动作键；车机定制输入法依据这两项渲染 Done 键，点击后触发 onEditorAction 完成确认。注意两点：公共组件 `dialog_edit.xml` 被多个弹窗复用，inputType=text 会统一生效，若有需要密码/数字专用键盘的调用方需再覆盖；`maxLength=6`（设备名称长度限制）保留不变。

## 复盘经验
- EditText 想要键盘"确定"键，`inputType` + `imeOptions=actionDone` 是成对前提，只配 flag 类 imeOptions 不够。
- 公共组件库（CommonTools）里的布局改动是全局性的，一处修改所有调用方生效，改前要排查复用面。
- 车机上输入法行为与手机 ROM 差异大，涉及输入的 UI 必须在真机键盘上验证动作键与收起行为。

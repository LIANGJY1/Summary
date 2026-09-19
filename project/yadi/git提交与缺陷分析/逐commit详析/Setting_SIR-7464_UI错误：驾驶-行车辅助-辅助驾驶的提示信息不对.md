# SIR-7464 · 驾驶-行车辅助-辅助驾驶的提示信息颜色不对
- **提交**：`1bdc8c30` | 2026-09-05 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（需求遗漏/文案色值类）

## 问题
辅助驾驶 info 弹窗内，三个预警入口按钮（追尾预警/车道偏离预警/交通预警）的文字颜色与正文提示文字颜色用反了，与 UI 式样不符。

## 根因分析
布局 `application/Setting/src/main/res/layout/dialog_assist_intro.xml` 中两组文本的颜色令牌（color token）被互换：三个按钮 `TextView`（`android:text="@string/rear_collision_warning"`、`@string/lane_deviation_warning`、`@string/traffic_warning_switch`，背景 `@drawable/selector_common_gray_btn`）误用了 `@color/text_default_press`（本应是常态文字色 `text_default_default`）；而下方详情正文 `adas_info_one`（`lineSpacingExtra="5sp"` 的说明段落）误用了 `@color/text_default_default`，式样要求的是 `text_default_press` 色。即"按钮文字用正文色、正文用按钮色"的对称性错误，符合缺陷库"字体颜色不对，按照 UI 修改颜色"的定性。

## 关键代码修改
改动文件：application/Setting/src/main/res/layout/dialog_assist_intro.xml（共 5 处 textColor 对调中的代表性 3 处）
```diff
--- application/Setting/src/main/res/layout/dialog_assist_intro.xml
                 android:text="@string/rear_collision_warning"
                 android:background="@drawable/selector_common_gray_btn"
-                android:textColor="@color/text_default_press"
+                android:textColor="@color/text_default_default"
                 android:textSize="@dimen/sp_24" />
...
                         android:layout_marginTop="@dimen/dp_20"
                         android:lineSpacingExtra="5sp"
-                        android:textColor="@color/text_default_default"
+                        android:textColor="@color/text_default_press"
                         android:text="@string/adas_info_one"
                         android:textSize="@dimen/sp_24" />
```

## 为什么能修复
把两组 textColor 令牌各归其位：按钮文字恢复 `text_default_default` 常态色，正文恢复 `text_default_press` 色，与式样书一致。纯资源引用对调，无逻辑风险；隐含教训是这两个 token 命名（default/press）与视觉语义（按钮/正文）并不直观对应，写布局时靠"抄隔壁行"极易整段带错。

## 复盘与经验
- 布局里逐个 View 手写 `textColor` 容易成片复制错 token，优先用 style（仓库 `view_styles.xml` 中已有带 textColor 的样式）或抽取公共 style，改一处即全部生效。
- "颜色不对"类 UI 缺陷的修复多为 token 对调，Review 时重点比对同屏控件间"按钮 vs 正文"的 token 分布是否对称，能快速发现这类错误。
- SIR-7464 一个单号在同一天由 3 个提交分别修滚动条、文字颜色、蓝牙二次确认，提测前需求走查不充分是共性背景。

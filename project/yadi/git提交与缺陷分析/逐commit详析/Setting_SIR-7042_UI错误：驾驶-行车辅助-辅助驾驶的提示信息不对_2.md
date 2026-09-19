# SIR-7042 · 驾驶-行车辅助-辅助驾驶提示信息 UI 错误
- **提交**：`6cb597e0` | 2026-09-03 | sgh | Setting | bugfix（UI 布局修正）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
车控车设 → 驾驶 → 行车辅助的辅助驾驶说明弹窗中，提示信息排版与 UI 设计稿不符（图文间距/行距/图片宽度不对）。

## 根因分析
纯布局参数与设计稿不一致：`dialog_assist_intro.xml` 中辅助驾驶说明图 `iv_detail` 使用 `match_parent` 撑满宽度，而设计稿要求固定 552dp；正文 `tv_detail_item` 的顶部间距 `dp_18`、行距 `4sp` 均小于设计值。属于视觉还原偏差，非逻辑问题。

## 关键代码修改
改动文件：`application/Setting/src/main/res/layout/dialog_assist_intro.xml`

```diff
--- application/Setting/src/main/res/layout/dialog_assist_intro.xml
                     <ImageView
                         android:id="@+id/iv_detail"
-                        android:layout_width="match_parent"
+                        android:layout_width="552dp"
                         android:layout_height="229dp"
                         android:scaleType="fitCenter"
                         android:src="@drawable/adas_info_bg" />
@@ 
                         android:id="@+id/tv_detail_item"
                         android:layout_width="match_parent"
                         android:layout_height="wrap_content"
-                        android:layout_marginTop="@dimen/dp_18"
-                        android:lineSpacingExtra="4sp"
+                        android:layout_marginTop="@dimen/dp_20"
+                        android:lineSpacingExtra="5sp"
```

## 为什么能修复
图片宽度固定为 552dp 后不再随容器拉伸变形，间距/行距对齐设计稿，视觉还原到位。此类改动无逻辑副作用，唯一隐患是硬编码 `552dp` 未提取 dimen，后续多语言/多分辨率适配时可能需要再调。

## 复盘与经验
- UI 还原类缺陷的高频根源：图片用 `match_parent` 交给容器决定宽度，而设计稿是定值；图文混排的间距/行距是 QA 视觉走查重点，开发时应逐项对照标注稿。
- 说明类弹窗建议把尺寸间距提取为 dimen 资源，减少硬编码，便于统一调整。
- 缺陷库该单根因写"Ui问题"，与 diff 一致；注意本提交与 `4c636710` 共用同一 Change-Id（`I164a540e...`），Gerrit 侧单号与提交对应关系存在复用/串号现象，追溯时以提交 hash 为准。

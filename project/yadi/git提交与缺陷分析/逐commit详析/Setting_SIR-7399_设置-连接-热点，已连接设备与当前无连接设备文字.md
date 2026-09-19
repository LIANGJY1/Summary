# SIR-7399 · 热点页"已连接设备"与"当前无连接设备"文字距离与 UI 不一致
- **提交**：`d345f410` | 2026-09-04 | dufan | Setting | bugfix（UI 间距）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
设置-连接-热点弹窗中，"已连接设备"列表与"当前无连接设备"（`no_wlan`）占位文字的垂直间距与设计稿不符。

## 根因分析
`dialog_connect_child.xml` 中 `no_wlan` 占位 TextView 采用 `layout_alignParentBottom` + `layout_marginBottom="@dimen/dp_120"` 固定贴底定位，120dp 边距偏大，导致与上方"已连接设备"区域之间的空隙不符合设计稿。

## 关键代码修改
改动文件：`application/Setting/src/main/res/layout/dialog_connect_child.xml`

```diff
--- application/Setting/src/main/res/layout/dialog_connect_child.xml
             android:layout_height="wrap_content"
             android:layout_alignParentBottom="true"
             android:layout_centerHorizontal="true"
-            android:layout_marginBottom="@dimen/dp_120"
+            android:layout_marginBottom="@dimen/dp_84"
             android:text="@string/no_wlan"
             android:textColor="@color/text_default_press"
             android:textSize="@dimen/sp_28" />
```

## 为什么能修复
贴底边距由 120dp 调整为 84dp，占位文字上移，与已连接设备区域间距对齐设计稿。单属性修改，无逻辑风险。

## 复盘与经验
- "空态占位文案"（无连接设备/无网络）常用 alignParentBottom + marginBottom 定位，该边距是 UI 走查高频不符点，开发时应直接采用设计标注值并优先使用 dimen 资源（本例 `dp_84`）。
- 间距类缺陷修改成本极低但返工频繁，根源多是开发凭感觉估值；对照标注稿逐项核对可一次性做对。

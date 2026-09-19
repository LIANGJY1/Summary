# SIR-6307 · 黑夜模式蓝牙音乐无音源时dock音乐卡片进度条颜色错误

- **提交**：`66471809` | 2026-08-24 | liujinfeng | SystemUI | bugfix（UI 资源修复）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 **问题取消** · 域 本地多媒体（单据最终被取消，但代码修复仍合入）

## 问题
黑夜模式下，蓝牙音乐无音乐源时，dock 栏音乐卡片的播放进度条 UI 颜色错误（与夜间设计稿不符）。

## 根因分析
夜间模式进度条走 `res/drawable-night/seekbar_progress.xml` 资源限定符机制。该文件里"进度面"图层之前用内联 shape + linear gradient 硬编码颜色 `#00EEEEEE → #33EEEEEE`——这是白天的浅灰系半透明渐变，直接照搬到了 night 资源里，导致黑夜模式下进度条底色/进度色与设计不符。缺陷库根因"夜间资源使用错误"，属资源实现层问题：夜间 drawable 本应是设计给定的夜间贴图，却被写成了日间配色渐变。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/drawable-night/seekbar_progress.xml、新增 application/SystemUI/src/main/res/drawable-mdpi/icon_seekbar_bg_night.png（**二进制更新**，夜间进度条贴图）
```diff
--- application/SystemUI/src/main/res/drawable-night/seekbar_progress.xml
@@ 进度面图层
-                <item>
+                <item android:drawable="@drawable/icon_seekbar_bg_night">
                     <shape android:shape="rectangle">
                         <size
                             android:width="364dp"
                             android:height="80dp" />
-                        <gradient
-                            android:type="linear"
-                            android:startColor="#00EEEEEE"
-                            android:endColor="#33EEEEEE"
-                            android:angle="0" />
                     </shape>
                 </item>
```

## 为什么能修复
夜间 `seekbar_progress.xml` 不再内联日间配色渐变，改引设计提供的夜间贴图 `icon_seekbar_bg_night`，颜色与夜间设计稿一致，视觉问题消除。资源限定符（drawable-night）机制本身没动，日间资源不受影响。隐患：贴图放在 `drawable-mdpi` 密度目录，高分屏上由系统缩放，可能出现轻微模糊，规范上更应放 `drawable-nodpi` 或按实际密度提供多套；另外该单缺陷状态为"问题取消"，修复与单据状态不同步，存在追溯困难。

## 复盘与经验
- 双主题（昼/夜）资源落地时，night 目录里的颜色/渐变必须独立出稿，绝不能复制日间实现改都不改。
- 设计给定的贴图与代码内联 shape/gradient 二选一时要统一约定，混用容易出现"一半资源一稿色"。
- 资源目录（mdpi/nodpi）选择要考虑缩放失真，纯 UI 贴图优先 nodpi。
- 缺陷单取消但修复已合入时，应在单据备注说明，避免后续回归测试对不上行为。

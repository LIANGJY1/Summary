# YD-392713 · UI错误：OSD条铃声图标显示错误问题修改

- **提交**：`8a2110f0` | 2026-06-29 | ljl | SystemUI | bugfix（图标资源替换）
- **缺陷库**：未关联单号（仅 YD 工单单号，无缺陷库记录）

## 问题
OSD 音量条上"铃声（Ring）"类图标形状/颜色与 UI 设计稿不符：旧铃声图标是"铃铛"造型，设计稿要求的是"喇叭+声波"造型；颜色也使用了写死的 `#993C4558`，与其他音量图标的令牌色不一致。

## 根因分析
以 diff 实际内容为准，问题集中在铃声系列的 4 个矢量资源（`vector_volume_ring`、`vector_volume_ring_mute` 及各自 `_unactive` 版）：其 pathData 画的是铃铛轮廓（顶部提手 + 底部摆锤弧 `M15.8804 30C...20.1196 30H23.1958...`），填充色硬编码 `#993C4558`（带透明度的深灰），与同族媒体/导航/蓝牙等图标使用的颜色令牌体系脱节。提交说明 [what]/[why] 均为"图标使用错误"，即切图时用错了图标版本。修复按设计稿重新导出矢量：替换 pathData 为喇叭+三段声波造型，填充色改 `@color/icon_default_press`，`viewportWidth/Height` 统一写成 `36.0`。

## 关键代码修改
改动文件：application/SystemUI/src/main/res/drawable/ 下 22 个 vector_volume_* 图标（铃声系列 4 个整体换造型，其余 18 个仅做 viewport 写法/颜色令牌的统一微调）

```diff
--- application/SystemUI/src/main/res/drawable/vector_volume_ring.xml
@@ 铃铛造型 → 喇叭+声波造型，颜色走令牌
-    <path android:fillColor="#993C4558"
-        android:pathData="M15.8804 30C16.1895 30.8735 17.0206 31.5 18 31.5C...（铃铛轮廓）" />
-    <path android:fillColor="#993C4558" android:fillType="evenOdd"
-        android:pathData="M19.5 4.59888C25.0041 5.3322 ...（铃铛主体）" />
+    <path android:fillColor="@color/icon_default_press" android:fillType="evenOdd"
+        android:pathData="M15.9507 3.46426C16.8694 2.60475 18.3293 2.98754 18.627 4.20986...（喇叭主体）" />
+    <path android:fillColor="@color/icon_default_press"
+        android:pathData="M25.1829 4.4999C30.6646 6.73232 34.5293 12.1129 34.5293 18.3969...（外层声波）" />
+    <path android:fillColor="@color/icon_default_press"
+        android:pathData="M22.5696 10.4999C26.0113 11.4928 28.5292 14.6641 28.5293 18.4254...（内层声波）" />
```
`vector_volume_ring_mute` 同步换为"喇叭+斜杠+声波"的静音造型；4 个铃声文件全部同步，保证 active/mute/unactive 三态一致。

## 为什么能修复
OSD 音量条按音频通道引用这些 drawable，替换 pathData 后铃声通道显示的即为设计稿的喇叭声波图标；颜色统一到 `icon_default_press` 令牌后与其它图标视觉一致，且夜间/主题换色自动跟随（呼应 3ba0d479/fac0a806 的令牌化路线）。纯资源替换，无逻辑风险。注意点：`_unactive`/`_mute`/普通三态共 4 个文件必须成组替换，本次已完整覆盖，未出现状态间造型不一致。

## 复盘与经验
- **图标三态成组管理**：`xxx`/`xxx_mute`/`xxx_unactive`（×常显/失效）是成套资源，替换时任何一份遗漏就会出现"状态切换图标跳变"。
- **矢量图标颜色一律走令牌**：`#993C4558` 这类带 alpha 的写死色是夜间适配死角，`@color/icon_default_*` 系列才是正解。
- **切图版本管理**："图标使用错误"多因设计稿迭代后旧 SVG 混入，建议切图导出按设计稿版本号归档、替换时核对 viewport 与 pathData 时代特征。

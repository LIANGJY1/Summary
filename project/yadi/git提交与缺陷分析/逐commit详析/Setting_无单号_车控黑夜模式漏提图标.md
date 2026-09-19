# 无单号 · 车控黑夜模式漏提图标

- **提交**：`d6c6dd54` | 2026-06-29 | shengguanghui | Setting | feature
- **关联单**：无

## 需求/目标
补齐车控页黑夜模式遗漏的资源：为 8 张仅在 `drawable-mdpi` 有白天版的图片补充 `drawable-night` 夜间版（HUD 亮度、座椅高度、驾驶模式大图），并把"近光灯/座椅上调"两张 mdpi 位图替换为语义色 vector。

## 实现结构
- 新增 `res/drawable-night/`：`adjust_height_left/right.png`、`hud_light_high/low.png`、`seat_main_bg.png`、`setting_drive_mode_com/eco/push/sport.png`（夜间配色大图，含 300-440KB 的驾驶模式整图）。
- 新增 `res/drawable/light_loomlight.xml`（近光灯 vector，fillColor 引用 `icon_default_default`）与 `seat_adjust_up_selected.xml`（座椅上调按钮 vector，三段 path：半透明白圆底 + 蓝描边 + 蓝色上箭头）。

数据流：系统切到夜间 → 资源限定符 `drawable-night` 命中 → 同名资源自动替换为夜间版本，代码零改动。

## 关键代码
```diff
# application/Setting/src/main/res/drawable/seat_adjust_up_selected.xml（新增）
+<vector xmlns:android="http://schemas.android.com/apk/res/android"
+    android:width="52dp" android:height="52dp"
+    android:viewportWidth="52" android:viewportHeight="52">
+  <path
+      android:pathData="M26,1L26,1A25,25 0,0 1,51 26L51,26A25,25 0,0 1,26 51L26,51A25,25 0,0 1,1 26L1,26A25,25 0,0 1,26 1z"
+      android:fillColor="@color/text_white_default"
+      android:fillAlpha="0.6"/>
+  <path ... android:strokeColor="@color/text_blue_press" />
+  <path ... android:fillColor="@color/text_blue_press" android:fillAlpha="0.6"/>
+</vector>
```
```diff
# 资源清单（Binary）
+ .../res/drawable-night/adjust_height_left.png   | Bin 0 -> 568 bytes
+ .../res/drawable-night/hud_light_high.png       | Bin 0 -> 932 bytes
+ .../res/drawable-night/setting_drive_mode_sport.png | Bin 0 -> 438290 bytes
```

实现讲解：漏提的本质是"白天版资源清单与夜间版没有做对齐检查"。位图改为语义色 vector 后，一份代码即可在两个模式下正确着色，是比"补一张夜间 png"更彻底的修法；但 400KB 级驾驶模式整图仍走双份 png，包体积代价需接受。

## 复盘与要点
- 可复用手法：图标类资源尽量 vector + 语义色，昼夜适配成本趋近于零；摄影图/复杂大图才用 drawable-night 双份。
- 流程要点：主题适配收尾时应有"drawable/ 与 drawable-night/ 文件名 diff"的机械检查，漏提靠人眼提测很难穷尽（本提交就是漏提补丁）。
- 遗留风险：这批 night png 后续在 37d1b73b、51192409 中又被删除替换（vector 化），说明此处补 png 是过渡方案，先补齐保功能、再逐步 vector 化。

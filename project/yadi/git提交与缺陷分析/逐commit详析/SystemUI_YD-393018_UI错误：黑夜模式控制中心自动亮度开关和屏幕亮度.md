# YD-393018 · 黑夜模式自动亮度开关与亮度条背景色与 UI 不一致
- **提交**：`d25cd629` | 2026-07-31 | liqingqing | SystemUI | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
黑夜模式下控制中心的自动亮度开关、屏幕亮度条的背景色几乎看不见，与 UI 稿不一致。

## 根因分析
`quick_button_selector_on.xml`（自动亮度开关选中态背景）与 `seek_progressdrawable.xml`（亮度条 track 背景）都用 `@color/gray_800_6` 作为底色。该颜色没有 `values-night` 覆盖值，黑夜模式下仍是 `#0F3C4558`（低透明度深蓝灰），叠在纯黑背景上对比度趋近于零，控件背景"消失"。根因不是颜色写错，而是**引用了一个不具备昼夜双值能力的语义色**；工程里已有昼夜双值的 `bg_segmentbutton_default`（白天同值、夜间 `#1ABBBDC1`），属于选色失误而非缺资源。

## 关键代码修改
改动文件：`application/SystemUI/src/main/res/drawable/quick_button_selector_on.xml`、`application/SystemUI/src/main/res/drawable/seek_progressdrawable.xml`（各 1 行）
```diff
--- application/SystemUI/src/main/res/drawable/quick_button_selector_on.xml
     <item android:state_selected="false">
         <shape android:shape="rectangle">
-            <solid android:color="@color/gray_800_6"/>
+            <solid android:color="@color/bg_segmentbutton_default"/>
             <corners android:radius="12dp"/>
--- application/SystemUI/src/main/res/drawable/seek_progressdrawable.xml
         <shape>
             <size android:height="60dp" />
             <corners android:radius="12dp" />
-            <solid android:color="@color/gray_800_6" />
+            <solid android:color="@color/bg_segmentbutton_default" />
```

## 为什么能修复
`bg_segmentbutton_default` 自带昼夜双值：白天与 `gray_800_6` 数值相同所以零回归，黑夜自动切到 `#1ABBBDC1`（带透明度的青灰色），在黑背景上有明确对比，开关与亮度条背景恢复可见。两处引用同色根因一并修掉，改动仅 2 行。隐患：若还有其他 drawable 用 `gray_800_6` 做夜间可见背景，会继续踩坑；更彻底的做法是给 `gray_800_6` 补 `values-night` 值（但会全局影响其所有使用处，需评估）。

## 复盘经验
- 颜色引用要区分"物理色"（gray_800_6）与"语义色"（bg_segmentbutton_default）：作为控件背景应引用带昼夜双值的语义色，物理色只用于明确不分昼夜的场合。
- "夜间看不见"不一定缺 `-night` 资源，更多是颜色本身没有夜间变体；排查顺序：查色值 → 查 values-night 覆盖 → 再查 drawable 回退。
- 修复时优先复用工程中已被验证的昼夜双值色，而不是新造颜色，可保证白天零回归。

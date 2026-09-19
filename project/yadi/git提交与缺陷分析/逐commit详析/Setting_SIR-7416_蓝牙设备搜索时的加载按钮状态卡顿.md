# SIR-7416 · 蓝牙设备搜索加载按钮旋转卡顿
- **提交**：`1ef67ce0` | 2026-09-08 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（rc：动画问题 → 修改动画属性）

## 问题
蓝牙设备搜索时，加载按钮的旋转动画一顿一顿、转速不匀，观感"卡顿"。

## 根因分析
`RotatingImageViewHelper` 用 `ObjectAnimator.ofFloat(imageView, "rotation", 0, 360)` 做无限循环旋转。ObjectAnimator 默认插值器是 `AccelerateDecelerateInterpolator`（先加速后减速）——对单次动画无感，但对 0→360 无限 RESTART 的旋转来说，每一圈都"慢-快-慢"再瞬间跳回 0 度重新加速，视觉上就是周期性的停顿/卡顿。修复即一行：`objectAnimator.setInterpolator(new LinearInterpolator())`，让角度随时间线性匀速变化，循环衔接处速度连续，无顿挫。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/utils/RotatingImageViewHelper.java
```diff
--- application/Setting/src/main/java/com/yadea/setting/utils/RotatingImageViewHelper.java
@@ -14,7 +15,8 @@ public class RotatingImageViewHelper {
     public RotatingImageViewHelper(ImageView imageView) {
         this.imageView = imageView;
         // 创建旋转动画：围绕中心旋转，从0度到360度
-        objectAnimator =  ObjectAnimator.ofFloat(this.imageView, "rotation", 0, 360);
+        objectAnimator = ObjectAnimator.ofFloat(this.imageView, "rotation", 0, 360);
+        objectAnimator.setInterpolator(new LinearInterpolator());
         objectAnimator.setDuration(1000);
         objectAnimator.setRepeatCount(ValueAnimator.INFINITE);
         objectAnimator.setRepeatMode(ValueAnimator.RESTART);
```

## 为什么能修复
加载指示类旋转的"卡顿"根因几乎都是默认加减速插值器在循环边界造成的速度不连续，换 LinearInterpolator 后每帧角速度恒定，RESTART 回绕时视觉无感。零副作用的标准修法。可延伸的注意点：车机低性能渲染下若仍有掉帧，再考虑把 rotation 动画放到硬件层或降低动画分辨率，但本单属插值器问题，与性能无关。

## 复盘经验
- 无限循环的旋转/位移动画必须显式设置 LinearInterpolator；ObjectAnimator 的默认插值器只适合单次播放。
- "循环动画看起来卡顿"先查插值器和 RESTART 回绕，而不是先怀疑主线程性能——两者修法完全不同。
- 此类通用小控件（旋转加载）值得全局排查一次用法，同项目其他页面大概率同样带病。

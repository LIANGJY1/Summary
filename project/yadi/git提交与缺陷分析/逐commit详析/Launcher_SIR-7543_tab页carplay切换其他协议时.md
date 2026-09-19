# SIR-7543 · tab页carplay切换其他协议时，二次确认弹窗从9s开始倒计时
- **提交**：`136adf35` | 2026-09-04 | dufan | Launcher | bugfix（cherry-pick 自 26cbad34）
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 手车互联

## 问题
CarPlay 切换其他互联协议时的二次确认弹窗，取消按钮倒计时第一次显示"9S"而不是预期的"10S"，整体倒计时少 1 秒。

## 根因分析
问题出在 `application/Launcher/.../utils/DialogUtil.kt` 的协议切换确认弹窗构造处。旧代码 `val totalTime = 10; countDownTimer = object : CountDownTimer(totalTime * 1000L, 1000L)`，`onTick(millisUntilFinished)` 中以 `second = (millisUntilFinished / 1000).toInt()` 拼接 `cancelText + "(" + second + "S)"` 刷新取消按钮文案。Android `CountDownTimer` 的第一次 `onTick` 并不立即执行，而是在经过一个 tick 间隔（1000ms）后才触发，此时 `millisUntilFinished` 已从 10000 递减到约 9000，`second` 首次显示即为 9；之后依次 8、7……1，到 `onFinish` 弹窗自动取消。于是"总时长 10 秒"的意图被系统性显示成 9 秒倒计时，这是 `CountDownTimer` 典型的首 tick 延迟 off-by-one。修复直接把总时长硬编码为 `11 * 1000L`：首个 tick 剩余约 10s 显示"10S"，末次 tick 显示"1S"，视觉上完整呈现 10 秒倒计时，同时顺带删除了不再使用的 `totalTime` 变量。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/utils/DialogUtil.kt
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/utils/DialogUtil.kt
-        val totalTime = 10
-        countDownTimer = object : CountDownTimer(totalTime * 1000L, 1000L) {
+        countDownTimer = object : CountDownTimer(11 * 1000L, 1000L) {
             @SuppressLint("SetTextI18n")
             override fun onTick(millisUntilFinished: Long) {
                 val second = (millisUntilFinished / 1000).toInt()
```

## 为什么能修复
`CountDownTimer` 的刻度从"经过 1 个间隔后"开始，要把 N 秒倒计时完整显示出来，总时长必须给 N+1 秒；改为 11s 后首个 tick 的 `millisUntilFinished/1000 == 10`，弹窗从"10S"开始倒数，与 UI 式样一致。副作用是弹窗实际存活时间变成约 11 秒（多 1 秒的启动缓冲），对"取消超时自动关窗"的交互无实质影响；隐患是 11 这个魔法数字没有注释说明"+1 是补偿首 tick 延迟"，后续维护者若想改成其他时长容易再踩同一个坑。

## 复盘与经验
- Android `CountDownTimer(totalMs, interval)` 首个 onTick 延迟一个 interval 触发，显示型倒计时需求传 N 秒会从 N-1 开始显示，需传 (N+1)*1000 或在 onTick 里 `ceil` 补偿。
- 用 `millisUntilFinished / 1000` 做整数除法时注意截断方向：剩余 9999ms 显示 9 而非 10，对"用户可见秒数"应考虑 `(ms + 999) / 1000` 向上取整。
- 倒计时类 bug 几乎都出在"计时刻度"与"显示数值"的边界映射上，写这类代码时应先画清楚 t=0、t=1s 两个时刻的显示值再落码。

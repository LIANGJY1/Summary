# 无单号 · CarPlay 首次连接判断与切换提示弹窗

- **提交**：`f536d005` | 2026-06-26 | dufan | Launcher/Setting | feature
- **关联单**：无（手车互联需求）

## 需求/目标
CarPlay 首次连接时弹出"是否切换"确认弹窗（10 秒倒计时），替代此前无提示的静默切换。

## 实现结构
改动文件：DialogUtil.java→DialogUtil.kt（重写）、CarConnectFragment.kt（+138 行主逻辑）、Constants.java、switch_dialog.xml 删除、Setting 侧 DeviceConnectManager.kt/BluetoothFragment.kt 联动、双语 strings 新增。
核心：新建 `DialogUtil` 单例承载切换弹窗，含 CountDownTimer 驱动的取消按钮倒计时（"取消(9S)"），弹窗存在期间防重入（`mSwitchDialog != null` 直接 return）。

## 关键代码
```kotlin
object DialogUtil {
    private var mSwitchDialog: TextDialog? = null
    fun showSwitchDialog(context, childFragmentManager, listener) {
        if (mSwitchDialog != null) return   // 防重入
        mSwitchDialog = TextDialog("", ..., "...确认", "...取消")
        ...
        countDownTimer = object : CountDownTimer(10_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                mSwitchDialog?.setCancelText(cancelText + "(" + second + "S)")  // 每秒刷新
            }
        }
    }
}
```

## 复盘与要点
- Java→Kotlin 重写 + 公共组件复用（上一提交 456d0002 刚加的 setCancelText 立即用上），组件-业务配套推进的完整闭环案例。
- 隐患埋点：单例持有 `mListener` 与 `mSwitchDialog`，倒计时结束后若不置空，后续所有弹窗请求被防重入分支吞掉——这正是后续"弹窗不消失/弹不出来"类缺陷（SIR-1429、SIR-4602 等）的同类结构成因，属于单例弹窗管理的固有风险。
- Setting 与 Launcher 双侧各持一份 DeviceConnectManager 的分叉从这一提交就开始了，半年后仍是互联缺陷的重复来源。

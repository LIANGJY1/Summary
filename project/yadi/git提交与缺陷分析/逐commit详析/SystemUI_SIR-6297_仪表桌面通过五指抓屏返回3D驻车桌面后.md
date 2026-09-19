# SIR-6297 · 五指抓屏返回3D驻车桌面后音乐卡片不跳转仍提示P档行车安全

- **提交**：`028ff10c` | 2026-08-24 | liujinfeng | SystemUI | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
仪表桌面通过五指抓屏返回 3D 驻车桌面（Android 界面）后，点击音乐卡片无法跳转网易云音乐，仍弹"为了行车安全，请在P档再打开应用"提示——而此时车已经停稳在驻车桌面场景。

## 根因分析
`NavBarFragment.handleMusicCardClick()` 原判断条件是 `Boolean.FALSE.equals(msgCmdControllerService.isPSwitch.getValue())`——即以**实时档位信号** `isPSwitch`（P 档开关）作为是否放行打开音乐应用的依据。缺陷库根因"跳转判断条件是档位，当前还是处于D档"：五指抓屏返回驻车桌面是**仪表形态/显示模式**的切换，车辆档位信号并未变成 P（本场景车辆实际仍处于 D 档滑行/等待状态），于是点击被行车安全拦截逻辑挡下。判断维度选错了：交互上"能否回到 Android 桌面并打开应用"取决于车机当前显示形态（仪表桌面 vs 3D 驻车桌面），而不是档位本身。修复改为查询 `settingsControllerService.getDisplayState()`，仅当显示形态不是 0 也不是 3（非允许形态）时才拦截。

## 关键代码修改
改动文件：application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java（+2/-1）
```diff
--- application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java
@@ handleMusicCardClick()
     private void handleMusicCardClick() {
-        if (Boolean.FALSE.equals(msgCmdControllerService.isPSwitch.getValue())) {
+        int displayState = settingsControllerService.getDisplayState();
+        if (displayState != 0 && displayState != 3) {
             ToastUtils.INSTANCE.showMsgICToast(requireContext(), getString(R.string.media_enter_toast));
             return;
         }
```

## 为什么能修复
拦截条件从"档位信号"换成"仪表显示形态"：五指抓屏切换到驻车桌面后 `displayState` 已处于允许值（0/3），音乐卡片点击放行，跳转网易云正常；真正的仪表桌面/行车形态下 `displayState` 不在允许集合，拦截依旧生效，行车安全策略未被放松。隐患：`displayState` 的 0/3 语义是魔法数字，未在代码中注释对应形态，后续形态扩展（新增枚举值）容易漏改；安全拦截从动态档位改为静态形态，若产品定义"某些形态下仍需按档位拦截"，需要再叠加条件。

## 复盘与经验
- 行车安全类拦截的条件要选对"维度"：本例中真正表达"用户可自由使用应用"的状态是车机显示形态，档位只是间接信号，两者在抓屏切桌面场景下会背离。
- 信号选型错误的一类信号是"必现但只在特定操作路径触发"，回归测试要覆盖跨入口到达同一页面的路径。
- 拦截放行集合用命名常量（如 DISPLAY_STATE_ALLOWED_SET）替代裸数字，可读性与可维护性都更好。

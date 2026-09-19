# 无单号 · 优化刻度进度条（控件重构）
- **提交**：`d54805e3` | 2026-07-02 | dufan | Setting | 优化（非缺陷修复）
- **缺陷库**：未关联单号

## 类型说明：自定义控件重构优化
本提交为刻度进度条控件的整体升级，无对应缺陷单（what/why/how 均为"优化刻度进度条"，影响等级 C）。

## 改动概要
- **新增** `application/Setting/src/main/java/com/yadea/setting/ui/widget/GearSwitchViewNew.kt`（937 行）：带刻度的分块式 seekbar，类注释说明核心改进为"每个档位对应一个独立激活块，块之间自动留出间距 gapWidth（2dp，与边框宽度一致），间距处露出滑轨背景色"；支持 `gearCount/minGear/currentGear`、`showGearNumbers/showTicks/showActiveTrack`、`realTimeCallback`、`thumbDrawable`、`ValueAnimator` 动画等。
- **替换监听器类型**：`DisplayFragment.kt`（屏幕亮度 `gearBright`）与 `VehicleControlFragment.kt`（HUD 亮度/高度/角度三处 `gearHeight/gearCorner/gearBright`）的 `GearSwitchView2.OnGearChangeListener/OnGearChangeStartListener` 全部切换为 `GearSwitchViewNew` 同名接口，业务回调体不变。
- **顺带调整**：`VehicleControlFragment.initView()` 移除 `mBinding.vcNestedScrollView.setupScrollFade(...)` 滚动渐隐；`gearswitchview_adjust_left_right.xml`、`gearswitchview_adjust_up_down.xml`、`seekbar_setting_hud_brightness_gear/new.xml`、`shape_btn_white_selected.xml` 等布局/图形属性同步适配。

## 评价
旧 `GearSwitchView2` 的刻度渲染与新视觉规格（分块+间距）不符，本次以新控件并行替换监听类型完成迁移，业务信号发送逻辑（`sendL2A`）未动，回归面集中在亮度/HUD 三条调节链路的拖动手感与显示。属于典型的"新控件替换、调用点平移"式重构。

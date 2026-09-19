# 无单号 · 移除无用代码资源（SystemUI HiCar 弹窗清理）

- **提交**：`78942903` | 2026-07-14 | dufan | SystemUI | feature
- **关联单**：无

## 需求/目标
**提交类型：死代码清理**。删除 SystemUI 中已废弃的 HiCar 设备/蓝牙切换弹窗及其引用，4 个文件纯删除 369 行、零新增。

## 实现结构
- 整文件删除：`base/HiCarSwitchDialog.java`（229 行，"HiCar设备和蓝牙切换弹窗（普通Dialog实现）"）+ 配套 `res/layout/dialog_switch_hi_car.xml`（79 行）。
- 引用清理：`cmdcontroller/systemsetting/SystemSettingsControllerService.java`（-9 行）与 `navbar/ui/NavBarFragment.java`（-52 行）中对该弹窗的调用、import 及残留死代码。

## 关键代码
```diff
--- a/application/SystemUI/src/main/java/com/android/systemui/navbar/ui/NavBarFragment.java（节选）
-（删除 HiCarSwitchDialog 的实例化、show 调用与相关字段，共 52 行）
```
实现讲解：与 Launcher 的 `7f7ae494` 同一清理工程的延续——换平台后 CarLink/HiCar 交互方案变更，旧弹窗类、布局、调用点三件套一次删净，不留注释尸体。

## 复盘与要点
- 清理顺序正确：先删调用点（Service/Fragment），再删类与 layout，保证每一步都可独立编译。
- 可复用检查法：删整类前全局搜类名（含 XML 中的自定义 View 引用与 Manifest 注册），本次类非 View、非组件，风险集中在调用点，已一并覆盖。

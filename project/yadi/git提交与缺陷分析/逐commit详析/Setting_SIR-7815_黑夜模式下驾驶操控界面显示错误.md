# SIR-7815 · 黑夜模式下驾驶操控界面显示错误
- **提交**：`4299b987` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设（根因：UI 图错误）

## 问题
夜间（drawable-night）模式下驾驶操控界面的驾驶模式配图显示错误——从效果看是 eco（经济）与 com（舒适）两张模式底图内容互换。

## 根因分析
改动对象是 `application/Setting/src/main/res/drawable-night/` 下的两张位图：`setting_drive_mode_eco.png` 与 `setting_drive_mode_com.png`。git stat 显示二者字节数恰好互换（379587 <-> 387866 bytes），即两张图片文件内容整体对调：命名 eco 的文件里装的是 comfort 的图、命名 com 的文件里装的是 eco 的图。根因是 UI 切图交付/拷贝时两张文件弄反，布局与代码引用关系本身没有问题。二进制更新，无代码逻辑改动。

## 关键代码修改
改动文件：application/Setting/src/main/res/drawable-night/setting_drive_mode_com.png；application/Setting/src/main/res/drawable-night/setting_drive_mode_eco.png
```diff
 .../res/drawable-night/setting_drive_mode_com.png  | Bin 379587 -> 387866 bytes
 .../res/drawable-night/setting_drive_mode_eco.png  | Bin 387866 -> 379587 bytes
```
（二进制更新：两张夜间模式 PNG 内容互换归位，代码与布局无改动。）

## 为什么能修复
将两张 PNG 内容对调后，资源名与图片内容重新对应，夜间模式下 eco/com 驾驶模式显示正确底图；白天模式（drawable）不受影响。无逻辑副作用；风险仅在于若切图源文件本身还有别的命名错误（如同尺寸图重复），需 UI 全量走查确认。

## 复盘与经验
- 成组切图（eco/com/sport 等）交付时"文件内容互换"是高发错误，且运行时表现为"图错了"而非崩溃，容易拖到 UI 走查才暴露；切图入库前应按文件名逐一核对内容。
- 从 git stat 的二进制字节数互换（A 变 B 的 size、B 变 A 的 size）可以快速识别"两张图对调"类缺陷。
- 夜间资源放 drawable-night 与白天同名资源强关联，命名错误只影响夜间模式，测试需覆盖日夜两套主题的同一界面。

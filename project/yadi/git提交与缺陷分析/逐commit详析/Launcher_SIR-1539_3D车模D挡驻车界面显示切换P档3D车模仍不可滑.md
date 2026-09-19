# SIR-1539 · 3D车模D挡驻车界面切回P档后车模仍不可滑动
- **提交**：`444b41ab` | 2026-07-09 | liqingqing | Launcher | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 3D车模

## 问题
3D 车模在 D 挡驻车/抓屏相关界面显示时切到 P 档，车模仍处于不可滑动（交互锁定）状态，无法恢复 P 档的自由交互。

## 根因分析
Kanzi 侧的车模交互由 `CarModel.Gear` 与 `CarModel.D_Desktop` 两个属性联合驱动：D 档驻车界面是"Gear=1 + D_Desktop=1"组合，只有两者同时复位到 0，交互才解锁。宿主两条下发路径都有缺口：
1. `KanziSignalMapping` 中 `CarPropertyIds.ENERGY_PCU_ACTUALGEAR` 真实档位信号处理：只 `sendToKanzi(GEAR, value)`，P 档（gear=0）时不清 `D_DESKTOP`；
2. `KanziDataSourceManager` 的 `ACTION_PANORAMA_DESKTOP` 桌面档位广播：只下发 `GEAR` extra，同样不动 `D_DESKTOP`。

于是切回 P 档后 Kanzi 残留在 `Gear=0、D_Desktop=1` 的非法组合，车模持续锁死。

## 关键代码修改
改动文件：application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java（+5/-1）、application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java（+6/-2）
```diff
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziDataSourceManager.java（桌面档位广播路径）
                 case ACTION_PANORAMA_DESKTOP:
                     if (isKanziConnected) {
-                        kanziManager.setValue("", KanziType.CarModel.GEAR, intent.getIntExtra(EXTRA_ENTER, 0));
+                        int gear = intent.getIntExtra(EXTRA_ENTER, 0);
+                        if (gear == 0) {
+                            kanziManager.setValue("", KanziType.CarModel.D_DESKTOP, 0);
+                        }
+                        kanziManager.setValue("", KanziType.CarModel.GEAR, gear);
                     }
--- application/Launcher/src/main/java/com/yadea/launcher/control/KanziSignalMapping.java（真实档位信号路径）
         signalHandlers.put(CarPropertyIds.ENERGY_PCU_ACTUALGEAR, event -> {
+            int gear = ((Number) event.getValue()).intValue();
             mHandler.postDelayed(() -> {
-                sendToKanzi(KanziType.CarModel.GEAR, event.getValue());
-            }, (int) event.getValue() == 1 ? 0 : 500);
+                if (gear == 0) {
+                    sendToKanzi(KanziType.CarModel.D_DESKTOP, 0);
+                }
+                sendToKanzi(KanziType.CarModel.GEAR, gear);
+            }, gear == 1 ? 0 : 500);
         });
```

## 为什么能修复
两条档位路径在收到 P 档（gear=0）时都先补发 `D_DESKTOP=0` 再发 `GEAR=0`，消除 `Gear=0、D_Desktop=1` 的残留组合；D 档保持只更新 Gear、不改 D_Desktop，D 档驻车界面逻辑不受影响。`D_DESKTOP=0` 先于 `GEAR=0` 下发保证了 Kanzi 侧状态复位顺序确定。隐患：两处路径逻辑重复，后续新增档位（如 R 档恢复）需同步改两处；且 postDelayed 500ms 窗口内若再次切档，仍可能短暂错序。

## 复盘与经验
- **组合状态必须成对复位**：当一个 UI 模式由多个属性（Gear×D_Desktop）联合表达时，退出路径必须把所有相关属性复位，只还原一个必然留脏状态。
- **同一状态的多条来源要统一维护**：真实车况信号与桌面广播两条路径写入同一组 Kanzi 属性，修复必须两路同步，最好收敛到单一入口防止再漏。
- **先复位从属状态再切主状态**：`D_DESKTOP=0` 先发、`GEAR=0` 后发的顺序约定值得沉淀为协议注释。

# SIR-5800 · 已配对设备再次出现在可配对设备列表
- **提交**：`1604941e` | 2026-08-13 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 关闭 · 域 车控车设

## 问题
手机蓝牙中已配对连接的设备，仍出现在"可配对设备"扫描列表中，界面显示异常。

## 根因分析
`component/Hardwarelibs/.../utils/BluetoothUtils.java` 的 `onPairWithKey(device, type, value)` 按配对变体（type：0/7=PIN、1=passkey、2/3=配对确认、6=OOB）分发处理，但 switch 结束后又无条件执行了一次 `device.setPairingConfirmation(true)`——对确认类变体（2/3）等于连续确认两次（缺陷库"发起了 2 次配对"），对 PIN/passkey/OOB 变体也额外注入了一次非预期确认。重复确认使协议栈为同一设备重复走配对流程，产生一条新的配对记录/扫描条目，已连接设备因此再次出现在可配对列表。

## 关键代码修改
改动文件：`component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/utils/BluetoothUtils.java`
```diff
--- component/Hardwarelibs/src/main/java/com/yadea/hardwarelibs/utils/BluetoothUtils.java
             case 1:
                 if (null == value) {
                     return;
                 }
                 passkey = Integer.parseInt(value);
                 setPasskey(device, passkey);
                 break;
-            case 2:
-            case 3:
-                device.setPairingConfirmation(true);
-                break;
-
-
             case 6:
                 setRemoteOutOfBandData(device);
                 break;
+            default:
+                device.setPairingConfirmation(true);
         }
-
-
-        device.setPairingConfirmation(true);
     }
```

## 为什么能修复
把 `setPairingConfirmation(true)` 从"所有变体结束后必发一次"收敛为"仅未显式处理的变体走 default 发一次"，每种配对变体只执行一次应有的应答，消除了重复确认导致的二次配对，已配对设备不再生成重复条目混入可配对列表。行为变化：PIN/passkey/OOB 变体不再附加自动确认（原属多余且有害）；2/3 变体由 case 分支改走 default，效果等价。隐患是若未来新增配对变体枚举，default 会静默自动确认，需留意。

## 复盘与经验
- switch 后再补一段"兜底公共操作"极易与分支内已做的操作重复——同一动作"一次在 case、一次在 switch 后"就是双发的来源；公共动作要么收敛进 default，要么删掉 case 内的副本。
- 蓝牙配对变体应答必须与系统枚举一一对应，多余的 `setPairingConfirmation` 不是无害的"保险"，而是触发重复配对的协议层副作用。
- 已配对设备重新出现在扫描列表，往往指向配对流程被重复触发，排查方向应从"列表过滤"转向"配对调用次数"。

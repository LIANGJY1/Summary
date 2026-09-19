# 无单号 · BTPhone 移除 Hardwarelibs 依赖

- **提交**：`b5135b54` | 2026-08-24 | dufan | BTPhone | feature（依赖治理）
- **关联单**：无

## 需求/目标
蓝牙电话应用与 Hardwarelibs（AnW 蓝牙扩展库）解耦：移除模块依赖及 HFP 语音识别设置的调用点。

## 实现结构
3 个文件、10 行删除：`build.gradle` 去掉 `implementation project(':component:Hardwarelibs')`；`BtPhoneApp.java` 删除启动时 `BtAnwManager.getInstance()` 预载；`TelecomForward.java` 删除来电接入时 `setVoiceRecognition(1)` 与通话释放时 `setVoiceRecognition(0)`（原注释说明是"解决蓝牙耳机无声问题"的 workaround）。

## 关键代码
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/telecom/dataexchange/TelecomForward.java
-                        //调用AnWBT_HFPAG_VoiceRecognition，设置语音识别为1，解决蓝牙耳机无声问题
-                        BtAnwManager.getInstance().setVoiceRecognition(1);
...
-                        //调用AnWBT_HFPAG_VoiceRecognition，释放语音识别
-                        BtAnwManager.getInstance().setVoiceRecognition(0);
```
实现讲解：与 BTMusic 的 `8b266ebd` 同属一批依赖治理。被删的 `setVoiceRecognition` 是针对"蓝牙耳机无声"的历史 workaround（通话时把 HFP AG 语音识别置 1、结束置 0）；删除意味着该问题被认为已在上游/协议栈侧解决，或该 workaround 本身引发副作用。

## 复盘与要点
- workaround 注释里写明"解决蓝牙耳机无声问题"，删除时未附回归结论——这类"拆 workaround"提交风险最高，建议提交信息关联验证过的缺陷单。
- 与 `8b266ebd` 两连提交逐应用清理，体现模块解耦宜小步分应用推进、便于单独回滚。

# VIR-36 · 蓝牙电话特权权限清单补齐（manifest 跟进提交）
- **提交**：`6eed7a90` | 2026-07-16 | hedeyuan | BTPhone | bugfix（配套 manifest 补丁）
- **缺陷库**：未关联单号（VIR-36 无缺陷库记录）

## 问题
`61d92d5b`（同日 22:10）让 BTPhone 直接调用 `BtAnwManager`/`AnWBT_HFPAG_VoiceRecognition` 并读取蓝牙特权信息，但 `BtPhoneApp` 进程尚未声明对应特权权限；本提交在 AndroidManifest 补上声明，属于前一提交的收尾配套。

## 根因分析
`61d92d5b` 已在 `whitelist/com.yadea.btphone.xml`（privapp-permissions 白名单）中加入 `LOCAL_MAC_ADDRESS`、`READ_PRIVILEGED_PHONE_STATE`、`MODIFY_PHONE_STATE`，但特权权限要生效必须同时满足两个条件：应用 manifest 中声明 + 平台 privapp 白名单中登记。仅改白名单不改 manifest，安装时 privapp-permissions 校验会报"declared in whitelist but not in manifest"类不一致，运行时直接调 `mBtAdapter.AnWBT_*` 接口会因权限缺失被拒。

## 关键代码修改
改动文件：`application/BTPhone/src/main/AndroidManifest.xml`

```diff
--- application/BTPhone/src/main/AndroidManifest.xml
+    <uses-permission android:name="android.permission.LOCAL_MAC_ADDRESS" />
+    <uses-permission android:name="android.permission.READ_PRIVILEGED_PHONE_STATE" />
+    <uses-permission android:name="android.permission.MODIFY_PHONE_STATE" />
```

## 为什么能修复
与白名单文件成对补齐后，特权权限声明链完整（manifest ↔ privapp-permissions 白名单一一对应），`BtAnwManager` 直调协议栈接口不再被权限拦截，VIR-36 的语音/来电恢复逻辑才真正可执行。纯权限声明，无行为副作用。

## 复盘与经验
- 车机 privapp 权限是"manifest 声明 + 系统白名单登记"双写模型，改动时两个文件必须同步提交，否则启动校验失败或运行时 SecurityException。
- 特权应用直调系统/协议栈接口前，先核对权限矩阵再写代码，可以避免"代码正确但权限不 through"的返工。

# VIR-190 · 补充 SystemUI 特权权限白名单，修复开机启动异常
- **提交**：`8ec85414` | 2026-08-15 | ljl | SystemUI | bugfix（配置类：权限白名单）
- **缺陷库**：未关联缺陷记录（VIR-190 无 defs 数组条目）

## 问题
SystemUI 缺少若干特权/系统权限的授予记录，开机启动时权限申请被拒导致启动异常。

## 根因分析
车机 Android 的特权应用权限必须登记在 `vendor/etc/permissions/` 下的 privapp-permissions 白名单 XML 中，安装时 PMS 才会把 `privileged|signature` 级权限授予该应用。SystemUI 近期新增功能（数字钥匙、丢失模式、车控信号监听等）引入了大量 `android.car.permission.*` 与 `android.permission.*` 特权权限（如 `CONTROL_KEYGUARD`、`MANAGE_ACTIVITY_STACKS`、`INTERACT_ACROSS_USERS_FULL`），而 `whitelist/com.android.systemui.xml` 未同步登记，开机启动时权限校验失败/能力不可用，出现启动异常。

## 关键代码修改
改动文件：`whitelist/com.android.systemui.xml`（纯配置，+71 行）
```diff
--- whitelist/com.android.systemui.xml
         <permission name="android.hardware.bluetooth" />
         <permission name="android.hardware.bluetooth_a2dp" />
         <permission name="android.hardware.bluetooth_headset" />
+        <permission name="android.car.permission.CAR_INFO"/>
+        <permission name="android.car.permission.CAR_POWER"/>
+        <permission name="android.car.permission.MONITOR_INPUT"/>
+        <permission name="android.car.permission.READ_PRIVILEGED_PHONE_STATE"/>
+        <permission name="android.permission.ACCESS_KEYGUARD_SECURE_STORAGE"/>
+        <permission name="android.permission.CONTROL_KEYGUARD"/>
+        <permission name="android.permission.DEVICE_POWER"/>
+        <permission name="android.permission.DISABLE_KEYGUARD"/>
+        <permission name="android.permission.INTERACT_ACROSS_USERS_FULL"/>
+        <permission name="android.permission.INTERNAL_SYSTEM_WINDOW"/>
+        <permission name="android.permission.MANAGE_ACTIVITY_STACKS"/>
+        ...（共 71 条 car/* 特权权限）
```

## 为什么能修复
白名单补齐后，PMS 在特权应用权限扫描阶段即可授予 SystemUI 声明的全部特权权限，开机初始化链路（锁屏、车控信号、窗口注入等）不再因权限被拒而异常。风险点：白名单一次性放开 71 条高敏感权限（INTERACT_ACROSS_USERS_FULL、INJECT_EVENTS 等），属 SystemUI 作为系统 UI 的常规授权范围，但需与 APK 实际声明的 uses-permission 对齐审计，避免超量授权。

## 复盘与经验
- 特权应用"新增功能=新增权限"，白名单必须与 AndroidManifest 同步演进；建议在 CI 中加一步 diff 校验 manifest 特权权限与白名单条目的一致性。
- "开机启动异常"类问题应优先检查 logcat 中的 Privileged Permission 三方校验报错（privapp-permissions），再查代码。
- 白名单文件虽是配置，其缺失造成的是启动级故障，应与代码同等纳入评审。

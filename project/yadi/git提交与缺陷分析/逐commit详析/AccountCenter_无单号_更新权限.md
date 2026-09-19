# 无单号 · [SIR-XXX] 更新权限
- **提交**：`b9d060cd` | 2026-09-18 | dufan | AccountCenter | feature（纯配置/权限声明）
- **关联单**：无（SIR-XXX 占位单号）

## 需求/目标
为账号中心应用补充特权权限声明：`android.permission.READ_PRIVILEGED_PHONE_STATE`。

## 实现结构
仅改动 `whitelist/com.yadea.accountcenter.xml`（+1）：privapp-permissions 白名单中新增一条 `READ_PRIVILEGED_PHONE_STATE`，使预置特权应用可合法持有该签名级权限（普通应用申请会被系统直接忽略/拒绝），通常服务于账号中心读取设备标识（IMEI 等）的需求。

## 关键代码
```xml
<!-- whitelist/com.yadea.accountcenter.xml -->
 <permission name="android.permission.READ_PHONE_STATE" />
+<permission name="android.permission.READ_PRIVILEGED_PHONE_STATE" />
 <permission name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

实现讲解：一行配置。`READ_PRIVILEGED_PHONE_STATE` 是 signature|privileged 级权限，必须同时在 Manifest 申请并进入系统 privapp-permissions 白名单才生效；本提交完成的是白名单侧。

## 复盘与要点
- **类型标注**：纯权限/配置变更，无代码。
- **白名单与 Manifest 要成对改**：本提交只动白名单，Manifest 侧应已在别处或同需求内申请；预置应用升级镜像时两处不同步会直接导致权限获取失败且无显式报错，排查成本高。
- **隐私合规提示**：特权级设备标识权限在量产车上通常需走车厂安全/合规评审，建议单号里登记依据（当前为 SIR-XXX 占位，追溯性弱）。

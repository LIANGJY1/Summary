# 无单号 · 能量中心 V3 UI 修改，Tbox 依赖包

- **提交**：`759278b7` | 2026-08-22 | liqingqing | EnergyManagement | feature（纯依赖库更新）
- **关联单**：无（Change-Id 与 618e9251 相同，为其补漏的依赖包提交）

## 类型说明
**纯二进制依赖更新**。仅新增 `component/commonlibs/vendor.hardware.tbox-V1-java.jar`（15,821 字节），无源码 diff。与 `618e9251` 共用 Change-Id `I026830db...`，属于同一需求拆出的"补依赖"提交——618e9251 的 TboxClientManager 引用了 `vendor.hardware.tbox.*` 接口，本提交补上该 HIDL java 存根包。

## stat
```
component/commonlibs/vendor.hardware.tbox-V1-java.jar | Bin 0 -> 15821 bytes
1 file changed
```
一句话：补入 TBOX vendor HIDL 接口的 java 存根 jar，使 618e9251 新增的 TboxClientManager 可编译；依赖与代码分两个提交落地，回溯时需按 Change-Id 关联。

# 无单号 · 更新NsrCommSdk.aar
- **提交**：`a9bab3b4` | 2026-09-04 | caohongliang | Component | 二进制依赖更新（非代码 bugfix）
- **缺陷库**：未关联单号

## 类型说明
纯 aar 二进制更新提交：`component/commonlibs/NsrCommSdk.aar` 由 53365 字节更新为 53463 字节，无任何源码改动（what/why/how 均为 NA）。该 SDK 为手车互联通讯库，结合同批次 SIR-7566（CarPlay 无联系人姓名时 SDK 以号码充当 displayName）等缺陷看，此类小版本更新通常携带互联协议侧的适配修正，但本次提交未附变更说明，具体内容不可从 diff 考证。

## 复盘与经验
- aar 类"黑盒"更新务必在提交信息或配套文档中登记来源版本与变更点，否则后续复盘（如此批次 SIR-7566 的"SDK 把号码作为 displayName 下发"）无法定位行为变化引入的时间点。

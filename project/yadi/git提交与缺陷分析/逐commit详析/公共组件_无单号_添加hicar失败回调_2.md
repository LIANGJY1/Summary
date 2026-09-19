# [SRS_BT_LinkSetting_012] · 添加 HiCar 失败回调（Component 侧 SDK 升级）

- **提交**：`ff1b4452` | 2026-08-06 | dufan | Component | feature
- **关联单**：SRS_BT_LinkSetting_012

## 需求/目标
升级创达 HiCar PSDK（`ts-hicar-psdk.aar`），为 SDK 增加"初始化失败"回调能力（`SDK_INIT_FAILED` 融合 UI 类型），配合应用层展示失败态。

## 实现结构
类型：**二进制依赖（aar）升级**。
单文件：`component/commonlibs/gestureconnectivity/ts-hicar-psdk.aar`（136506 → 136628 字节，二进制变更），无源码改动。
一句话：由创达重新打包的 psdk aar 替换，新增 HiCar 初始化失败回调；应用侧消费见同需求 `02f0e8db`。

## 关键代码
（二进制 diff，略）

## 复盘与要点
- aar 升级与消费代码（02f0e8db）拆成两个提交且组件先行 3 分钟，依赖关系清晰：先换库、再用新枚举，保证每一步都可编译。
- 建议在提交说明里附上 SDK 版本号与 changelog，本提交仅靠标题无法追溯 aar 具体差异。

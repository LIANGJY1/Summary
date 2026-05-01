# 车载K歌SRS生成与优化工作流总结

本文总结了本次“车载K歌软件”Excel SRS 生成、规则更新、文档优化与校验的完整流程。

## 1. 总体工作流

```mermaid
flowchart TD
    A[用户提出需求] --> B[加载 excel-srs-skill]
    B --> C[读取共享规则]
    C --> C1[srs-workflow-rules]
    C --> C2[excel-template-rules]
    C --> C3[项目场景规则]
    C --> D[模板发现与结构分析]
    D --> D1[导出CSV/表格中间产物]
    D --> D2[解析OOXML元数据]
    D2 --> D3[批注/合并单元格/校验/命名范围]
    D1 --> E[需求抽取与规范化]
    E --> E1[业务目标]
    E --> E2[角色/用户]
    E --> E3[功能需求]
    E --> E4[非功能需求]
    E --> E5[风险/未决问题]
    E3 --> F[映射到Excel模板]
    E4 --> F
    F --> G[写入工作簿副本]
    G --> H[结构与枚举校验]
    H --> H1[可打开性检查]
    H --> H2[合并单元格检查]
    H --> H3[下拉枚举检查]
    H --> I[输出最终文件]
```

## 2. 规则更新工作流

```mermaid
flowchart TD
    A[发现功能页需要分组展示] --> B[更新 skill 文档]
    A --> C[更新共享 workflow rules]
    A --> D[更新共享 template rules]
    B --> B1[明确一级需求可对应多个二级需求]
    B --> B2[明确连续同组一级需求需纵向合并]
    C --> C1[写入分组映射规则]
    C --> C2[限制合并范围仅限数据区]
    D --> D1[定义3功能需求列语义]
    D --> D2[定义合并边界与保留规则]
    B1 --> E[形成新的生成契约]
    C1 --> E
    D1 --> E
    E --> F[按新规则重生工作簿]
```

## 3. 功能需求页优化工作流

```mermaid
flowchart TD
    A[原始功能需求] --> B[能力分组]
    B --> C1[点歌与内容管理]
    B --> C2[演唱与效果呈现]
    B --> C3[座舱协同与安全控制]
    B --> C4[账号与内容留存]
    C1 --> D1[歌曲搜索与点播]
    C1 --> D2[弱网降级与本地缓存]
    C1 --> D3[待唱队列与会话恢复]
    C2 --> D4[歌词与伴奏同步]
    C2 --> D5[麦克风接入与音效调节]
    C2 --> D6[评分与结果展示]
    C3 --> D7[角色识别与权限控制]
    C3 --> D8[多音源混音与让音]
    C3 --> D9[行车场景操作限制]
    C4 --> D10[录音保存与分享授权]
    D1 --> E[写入3功能需求表]
    D2 --> E
    D3 --> E
    D4 --> E
    D5 --> E
    D6 --> E
    D7 --> E
    D8 --> E
    D9 --> E
    D10 --> E
    E --> F[合并同组一级需求单元格]
    F --> G[形成最终可阅读层级]
```

## 4. 校验工作流

```mermaid
flowchart TD
    A[生成工作簿] --> B[重新打开文件]
    B --> C[检查sheet顺序]
    B --> D[检查合并单元格]
    B --> E[检查批注/校验/命名范围]
    B --> F[检查枚举字段]
    C --> G{通过?}
    D --> G
    E --> G
    F --> G
    G -- 是 --> H[记录manifest]
    G -- 否 --> I[修正后重新生成]
    H --> J[交付最终Workbook]
```

## 5. 本次产物路径

- 最终工作簿：`/home/liang/Project/Reachauto/AI/demo/.sisyphus/runs/20260424-car-karaoke-srs/软件需求规格说明书-简化版_车载K歌软件_智能座舱.xlsx`
- 工作流总结：`/home/liang/Project/Reachauto/AI/demo/.sisyphus/runs/20260424-car-karaoke-srs/workflow-summary.md`
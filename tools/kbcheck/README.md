# kbcheck — 知识库机械规范体检

对 `knowledge-base/` 全库执行 WRITING-GUIDE 可机械验证的十项检查并输出汇总报告。

```bash
python3 tools/kbcheck/kbcheck.py                # 只报告
python3 tools/kbcheck/kbcheck.py --fix-blank    # 同时自动收敛连续空行
python3 tools/kbcheck/kbcheck.py --root <路径>   # 指定其他知识库根
```

## 检查项

1. 分号串列（正文/列表项 ≥2 个全角分号连接并列项）
2. 粗体叠行内码（**粗体** 内含反引号）
3. 连续空行（题间多余空行；`--fix-blank` 自动收敛，代码块豁免）
4. 日期元数据（引言中「日期+变更动词」残留）——粗筛定位，技术语境词（如「DMA-BUF 迁移」）需人工排除
5. 代码块缺语言标注
6. 图片引用存在性（URL 解码后核对）
7. 内部 md 链接死链
8. 题号连续性（含全角冒号题干检测）
9. README 计数链（声明总题数 vs 实际）
10. 状态标记分布（只统计，不做合规判断——无标记题属历史遗留，按状态冻结令保持）

## 口径说明

- 依据行（「依据：」开头）、引言（「>」开头）、代码块内豁免分号检查。
- 「其一…其二…」序词挤行、单分号并列结论等语义级问题无法机械判定，需人工按 WRITING-GUIDE 第 7/9 节审查。
- 检查项与 `knowledge-base/WRITING-GUIDE.md` 的验收条目一一对应；规范变更时同步更新本工具。

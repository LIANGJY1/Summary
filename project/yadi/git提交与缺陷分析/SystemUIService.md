<!-- 生成日期 2026-09-18，由 git 提交与缺陷库自动关联生成 -->

### SIR-8231 · 运行monkey脚本后，偶现底部状态栏左下角消失，车机界面位置往上偏移

- **提交**：[`SIR-8231_运行monkey脚本后，偶现底部状态栏左下角消失`](逐commit详析/SystemUIService_SIR-8231_运行monkey脚本后，偶现底部状态栏左下角消失.md) · 2026-09-18 · caohongliang
- **根因类别**：崩溃与异常防护
- **问题**：应用显示区域异常
- **根因**（缺陷库）：windowmanager拿的context是display2的，导致状态栏和dock添加到display2上了
- **根因**（提交）：windowmanager获取到了错误的context，状态栏显示到display2上了
- **修复**（缺陷库）：App这边systemui针对该问题做了兜底处理，防止windowmanager拿到错误context，指定display0的context
- **修复**（提交）：指定获取display0的context
- **缺陷库**：状态 待测试验证 · 等级 B · 偶现-低于10% · 域 系统需求


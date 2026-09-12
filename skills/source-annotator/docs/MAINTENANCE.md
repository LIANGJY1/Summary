# 维护约定

- **权威源**：`~/.agents/skills/source-annotator/`（唯一改动入口）。
- **发布副本**：`~/Project/MyProject/Summary/skills/source-annotator/`，改完整目录单向同步：`cp -r ~/.agents/skills/source-annotator/. ~/Project/MyProject/Summary/skills/source-annotator/`（Summary 在 git 下，负责留史；不在副本上直接改）。
- 同步后跑一次 `python3 scripts/check_annotations.py --self-test` 确认副本完整。

- 同步后校验两条：`python3 scripts/check_annotations.py --self-test`；`python3 scripts/check_annotations.py --self-test` 通过后在实际仓库任选文件跑一次并加 `--mirror ~/Project/MyProject/Summary/skills/source-annotator` 确认副本一致。
- **Backlog（P2，按需启动）**：①触发评测（~20 条含 near-miss、每条 3 次、60/40 切分，方法见《全网高质量Skills调研与写作指南》附录 C）；②输出基线评测（素材：AAOS13 输入系统四批标注的返工记录，with/without 对照）；③回指编号存在性检查（"见 X 处 N"的 N 在总论中存在——等第二次真实险情再立项，启发式解析误报风险高）。

#!/usr/bin/env python3
"""校验 knowledge-base 大类文档的结构一致性。

对每个大类文档检查：
1. `## 目录` 的链接文本与正文 `##` 条目节一一对应（双向，防目录漂移）
2. 文内相对链接（./x.md）目标存在

用法：
    python3 check_kb.py [路径 ...]   # 目录或 .md 文件；缺省校验固定知识库
退出码：0 全部通过；1 有结构问题；2 没找到可校验文档。
"""

import re
import sys
from pathlib import Path

DEFAULT_KB = Path("/home/liang/Project/MyProject/Summary/project/knowledge-base")
SKIP_FILES = {"CONTEXT.md", "README.md"}
STRUCTURAL = {"边界", "规则", "目录"}


def check_doc(path: Path) -> list[str]:
    issues: list[str] = []
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)  # 条目模板注释不参与校验

    entries = [h.strip() for h in re.findall(r"^## (.+)$", text, re.M)
               if h.strip() not in STRUCTURAL]
    toc = re.search(r"^## 目录\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not toc:
        return [f"{path}: 缺少 `## 目录` 节"]
    toc_links = re.findall(r"^- \[(.+?)\]\(#", toc.group(1), re.M)

    for link in toc_links:
        if link not in entries:
            issues.append(f"{path}: 目录指向不存在的条目节：{link}")
    for entry in entries:
        if entry not in toc_links:
            issues.append(f"{path}: 条目节未登记目录：{entry}")
    for target in re.findall(r"\]\((\.{1,2}/[^)#]+)\)", text):
        if not (path.parent / target).resolve().exists():
            issues.append(f"{path}: 相对链接目标不存在：{target}")
    return issues


def main() -> int:
    args = sys.argv[1:]
    roots = [Path(a) for a in args] or [DEFAULT_KB]
    docs: list[Path] = []
    for p in roots:
        if p.is_dir():
            docs.extend(sorted(p.glob("*.md")))
        elif p.suffix == ".md":
            docs.append(p)
        else:
            print(f"跳过非 Markdown 路径：{p}", file=sys.stderr)
    docs = [d for d in docs if d.name not in SKIP_FILES and d.exists()]
    if not docs:
        print("没有找到可校验的 .md 文档", file=sys.stderr)
        return 2

    issues = [line for d in docs for line in check_doc(d)]
    for line in issues:
        print(line)
    print(f"\n校验 {len(docs)} 篇：{'发现 %d 处结构问题' % len(issues) if issues else '全部通过'}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())

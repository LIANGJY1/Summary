#!/usr/bin/env python3
"""检查 knowledge-base 的结构、目录锚点、链接与路由注册。

默认只检查根目录的条目型大类；Language 是散文 profile，使用
``--profile language`` 检查链接，或由 ``--profile all`` 自动识别。
脚本只读，不会替文档修复任何内容。
"""

from __future__ import annotations

import argparse
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

DEFAULT_KB = Path("/home/liang/Project/MyProject/Summary/project/knowledge-base")
SKIP_FILES = {"CONTEXT.md", "README.md", "ROUTING.md"}
STRUCTURAL = {"边界", "规则", "目录"}


def _anchor_key(text: str) -> str:
    """去掉渲染器差异（标点、下划线、路径分隔符）后比较锚点语义。"""
    return "".join(char.lower() for char in text if char.isalnum() or unicodedata.category(char).startswith("L"))


def _links(text: str):
    return re.finditer(r"\[[^\]]+\]\(([^)]+)\)", text)


def _check_links(path: Path, text: str) -> list[str]:
    issues: list[str] = []
    for match in _links(text):
        target = match.group(1).strip().split("#", 1)[0]
        if not target or target.startswith(("#", "http://", "https://", "mailto:", "file:")):
            continue
        target_path = (path.parent / target).resolve()
        if not target_path.exists():
            issues.append(f"{path}: 链接目标不存在：{target}")
    return issues


def check_doc(path: Path, profile: str = "entry") -> list[str]:
    issues: list[str] = []
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        return [f"{path}: 无法读取 UTF-8 文档：{exc}"]

    text = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    issues.extend(_check_links(path, text))
    if profile == "language":
        return issues

    headings = re.findall(r"^## (.+?)\s*$", text, re.M)
    heading_counts = Counter(headings)
    for title, count in heading_counts.items():
        if count > 1:
            issues.append(f"{path}: 重复条目标题（{count} 次）：{title}")

    missing = STRUCTURAL.difference(headings)
    for title in sorted(missing):
        issues.append(f"{path}: 缺少 `## {title}` 节")
    if not re.search(r"^# .+", text, re.M):
        issues.append(f"{path}: 缺少一级标题")
    if not re.search(r"^> .+", text, re.M):
        issues.append(f"{path}: 缺少维护者引言 blockquote")

    entries = [title for title in headings if title not in STRUCTURAL]
    toc = re.search(r"^## 目录\s*$(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not toc:
        return issues + [f"{path}: 缺少 `## 目录` 节"]

    toc_pairs = re.findall(r"^- \[([^]]+)\]\(#([^ )]+)\)\s*$", toc.group(1), re.M)
    toc_titles = [title for title, _ in toc_pairs]
    for title, anchor in toc_pairs:
        if title not in entries:
            issues.append(f"{path}: 目录指向不存在的条目节：{title}")
        if _anchor_key(anchor) != _anchor_key(title):
            issues.append(f"{path}: 目录锚点无法对应条目：{title} → #{anchor}")
    for title, count in Counter(toc_titles).items():
        if count > 1:
            issues.append(f"{path}: 目录重复登记（{count} 次）：{title}")
    for title, count in Counter(entries).items():
        if count != toc_titles.count(title):
            issues.append(f"{path}: 条目与目录登记次数不一致：{title}（正文 {count}，目录 {toc_titles.count(title)}）")
    return issues


def check_routing(kb_root: Path, routing: Path) -> list[str]:
    """检查路由表链接存在，并确保根级条目型大类已注册。"""
    issues: list[str] = []
    try:
        text = routing.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        return [f"{routing}: 无法读取 UTF-8 路由表：{exc}"]
    registered: set[str] = set()
    for target in re.findall(r"\]\((\./[^)#]+)\)", text):
        resolved = (routing.parent / target).resolve()
        if not resolved.exists():
            issues.append(f"{routing}: 路由链接目标不存在：{target}")
        registered.add(resolved.as_posix())
    for doc in sorted(kb_root.glob("*.md")):
        if doc.name in SKIP_FILES:
            continue
        if doc.resolve().as_posix() not in registered:
            issues.append(f"{routing}: 根级大类未登记：{doc.name}")
    return issues


def _docs(roots: list[Path], profile: str) -> list[tuple[Path, str]]:
    docs: list[tuple[Path, str]] = []
    for root in roots:
        if root.is_file() and root.suffix == ".md":
            inferred = "language" if "language" in root.parts else profile
            docs.append((root, inferred))
        elif root.is_dir():
            for doc in sorted(root.glob("*.md")):
                if doc.name in SKIP_FILES:
                    continue
                inferred = "language" if "language" in doc.parts else profile
                docs.append((doc, inferred))
        else:
            print(f"跳过不存在或非 Markdown 路径：{root}", file=sys.stderr)
    return docs


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="要检查的 .md 或目录")
    parser.add_argument("--profile", choices=("entry", "language", "all"), default="all")
    parser.add_argument("--routing", type=Path, help="额外检查的 ROUTING.md")
    args = parser.parse_args(argv)
    roots = args.paths or [DEFAULT_KB]
    profile = "entry" if args.profile == "all" else args.profile
    docs = _docs(roots, profile)
    issues = [issue for doc, inferred in docs for issue in check_doc(doc, inferred)]
    if args.routing:
        issues.extend(check_routing(args.routing.parent, args.routing))
    for issue in issues:
        print(issue)
    print(f"\n校验 {len(docs)} 篇：{'发现 %d 处结构问题' % len(issues) if issues else '全部通过'}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())

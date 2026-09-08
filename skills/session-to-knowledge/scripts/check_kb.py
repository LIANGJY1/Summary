#!/usr/bin/env python3
"""检查 knowledge-base 的结构、目录锚点、链接、脱敏与路由注册。

脚本只读。默认递归扫描知识库；``--profile all`` 按路径选择 profile。
显式给出的不存在路径、空目录和 profile/路径冲突都会失败，避免出现假绿。
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from collections import Counter
from pathlib import Path

DEFAULT_KB = Path("/home/liang/Project/MyProject/Summary/project/knowledge-base")
SKIP_FILES = {"CONTEXT.md", "README.md", "ROUTING.md"}
SKIP_DIRS = {"docs/adr"}
STRUCTURAL = {"边界", "规则", "目录"}
SECRET_PATTERNS = (
    re.compile(r"-----BEGIN (?:RSA|EC|OPENSSH|DSA|PGP) PRIVATE KEY-----"),
    re.compile(r"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    re.compile(r"\b(?:ghp|github_pat|glpat)-[A-Za-z0-9_\-]{20,}\b"),
    re.compile(r"(?i)\b(?:api[_-]?)?(?:password|passwd|token|secret)\s*[:=]\s*['\"]?[A-Za-z0-9+/=_\-]{16,}"),
)


def _anchor_key(text: str) -> str:
    """按 Markdown 渲染器常见规则比较标题与目录锚点的语义。"""
    return "".join(char.lower() for char in text if char.isalnum() or unicodedata.category(char).startswith("L"))


def _masked(text: str) -> str:
    return re.sub(r"\[已脱敏[^]]*\]", "", text)


def _strip_fenced(text: str) -> str:
    lines: list[str] = []
    fenced = False
    for line in text.splitlines():
        if re.match(r"^\s*(```|~~~)", line):
            fenced = not fenced
            continue
        if not fenced:
            lines.append(line)
    return "\n".join(lines)


def _links(text: str):
    return re.finditer(r"\[[^\]]+\]\(([^)]+)\)", text)


def _check_links(path: Path, text: str, kb_root: Path | None = None) -> list[str]:
    issues: list[str] = []
    root = (kb_root or path.parent).resolve()
    for match in _links(text):
        raw = match.group(1).strip()
        target, _, fragment = raw.partition("#")
        if not target or target.startswith(("http://", "https://", "mailto:", "file:")):
            continue
        if Path(target).is_absolute():
            issues.append(f"{path}: 不允许绝对本地链接：{raw}")
            continue
        target_path = (path.parent / target).resolve()
        try:
            target_path.relative_to(root)
        except ValueError:
            issues.append(f"{path}: 链接越过知识库边界：{raw}")
            continue
        if not target_path.exists():
            issues.append(f"{path}: 链接目标不存在：{raw}")
        elif target_path.is_dir() and not target.endswith("/"):
            issues.append(f"{path}: 链接目标是目录而非文档：{raw}")
        elif fragment and target_path.suffix == ".md":
            try:
                headings = re.findall(r"^#{1,6} (.+?)\s*$", _strip_fenced(target_path.read_text(encoding="utf-8")), re.M)
            except (OSError, UnicodeError):
                continue
            if not any(_anchor_key(fragment) == _anchor_key(title) for title in headings):
                issues.append(f"{path}: 链接锚点不存在：{raw}")
    return issues


def _check_secrets(path: Path, text: str) -> list[str]:
    clean = _masked(text)
    return [f"{path}: 疑似敏感信息（仅报告模式，不回显原文）" for pattern in SECRET_PATTERNS if pattern.search(clean)]


def _check_supersedes(path: Path, text: str, kb_root: Path | None = None) -> list[str]:
    issues: list[str] = []
    for match in re.finditer(r"\*\*supersedes\*\*\s*[:：]\s*(?:\[[^\]]+\]\(([^)]+)\)|`([^`]+)`)", text, re.I):
        target = match.group(1) or match.group(2) or ""
        if target.startswith("#"):
            continue
        issues.extend(_check_links(path, f"[supersedes]({target})", kb_root))
    return issues


def check_doc(path: Path, profile: str = "entry", kb_root: Path | None = None) -> list[str]:
    issues: list[str] = []
    try:
        text = path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        return [f"{path}: 无法读取 UTF-8 文档：{exc}"]

    clean = re.sub(r"<!--.*?-->", "", text, flags=re.S)
    structural_text = _strip_fenced(clean)
    issues.extend(_check_links(path, structural_text, kb_root))
    issues.extend(_check_supersedes(path, structural_text, kb_root))
    issues.extend(_check_secrets(path, clean))
    if profile == "language":
        return issues

    headings = re.findall(r"^## (.+?)\s*$", structural_text, re.M)
    heading_counts = Counter(headings)
    for title, count in heading_counts.items():
        if count > 1:
            issues.append(f"{path}: 重复条目标题（{count} 次）：{title}")

    missing = STRUCTURAL.difference(headings)
    for title in sorted(missing):
        issues.append(f"{path}: 缺少 `## {title}` 节")
    if not re.search(r"^# .+", structural_text, re.M):
        issues.append(f"{path}: 缺少一级标题")
    if not re.search(r"^> .+", structural_text, re.M):
        issues.append(f"{path}: 缺少维护者引言 blockquote")

    entries = [title for title in headings if title not in STRUCTURAL]
    toc = re.search(r"^## 目录\s*$(.*?)(?=^## |\Z)", structural_text, re.M | re.S)
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
    """检查路由链接、根级条目注册和语言目录注册。"""
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


def _infer_profile(path: Path, requested: str) -> tuple[str, str | None]:
    in_language = "language" in path.parts
    if requested == "all":
        return ("language" if in_language else "entry", None)
    if requested == "language" and not in_language:
        return requested, f"{path}: --profile language 只能检查 language/ 下文档"
    if requested == "entry" and in_language:
        return requested, f"{path}: --profile entry 不能检查 language/ 下文档"
    return requested, None


def _docs(roots: list[Path], profile: str, kb_root: Path) -> tuple[list[tuple[Path, str]], list[str]]:
    docs: list[tuple[Path, str]] = []
    issues: list[str] = []
    for root in roots:
        if not root.exists():
            issues.append(f"路径不存在：{root}")
        elif root.is_file():
            if root.suffix != ".md":
                issues.append(f"不是 Markdown 文档：{root}")
            else:
                inferred, mismatch = _infer_profile(root, profile)
                docs.append((root, inferred))
                if mismatch:
                    issues.append(mismatch)
        elif root.is_dir():
            found = [
                doc for doc in sorted(root.rglob("*.md"))
                if doc.name not in SKIP_FILES
                and not any(str(part) in SKIP_DIRS for part in doc.relative_to(root).parents)
            ]
            if not found:
                issues.append(f"目录未找到可检查的 Markdown 文档：{root}")
            for doc in found:
                inferred, mismatch = _infer_profile(doc, profile)
                docs.append((doc, inferred))
                if mismatch:
                    issues.append(mismatch)
    return docs, issues


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("paths", nargs="*", type=Path, help="要检查的 .md 或目录")
    parser.add_argument("--profile", choices=("entry", "language", "all"), default="all")
    parser.add_argument("--routing", type=Path, help="额外检查的 ROUTING.md")
    parser.add_argument("--routing-only", action="store_true", help="只检查路由，不扫描默认文档")
    parser.add_argument("--kb-root", type=Path, default=DEFAULT_KB, help="知识库根目录（默认使用本机约定路径）")
    parser.add_argument("--format", choices=("text", "json"), default="text")
    args = parser.parse_args(argv)
    if args.routing_only and not args.routing:
        parser.error("--routing-only 需要同时提供 --routing")
    roots = [] if args.routing_only else (args.paths or [args.kb_root])
    docs, issues = _docs(roots, args.profile, args.kb_root)
    issues.extend(issue for doc, inferred in docs for issue in check_doc(doc, inferred, args.kb_root))
    if args.routing:
        issues.extend(check_routing(args.kb_root, args.routing))
    if args.format == "json":
        print(json.dumps({"documents": len(docs), "issues": issues, "ok": not issues}, ensure_ascii=False, indent=2))
    else:
        for issue in issues:
            print(issue)
        print(f"\n校验 {len(docs)} 篇：{'发现 %d 处问题' % len(issues) if issues else '全部通过'}")
    return 1 if issues else 0


if __name__ == "__main__":
    raise SystemExit(main())

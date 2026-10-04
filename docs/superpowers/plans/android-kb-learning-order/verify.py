#!/usr/bin/env python3
"""Verify this ordering migration against Git's pre-migration document bodies."""

import csv
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
KB = ROOT / "knowledge-base/01-android"
HERE = Path(__file__).resolve().parent
Q = re.compile(r"(?m)^\*\*Q(\d+):")
LINK = re.compile(r"\[[^\]\n]*\]\(([^)\s]+)\)")


def blocks(text):
    hits = list(Q.finditer(text))
    return {int(hit.group(1)): text[hit.start() : (hits[i + 1].start() if i + 1 < len(hits) else len(text))]
            for i, hit in enumerate(hits)}


def normalized(text):
    # The allowed edits to a Q block are its number and references to moved Qs/files.
    text = Q.sub("**Q#:", text, count=1)
    text = re.sub(r"\[[^\]\n]+\.md\]\([^)\s]+\.md\)", "[MOVED_MD_REFERENCE]", text)
    text = re.sub(r"(?<![\w-])\d\d-([a-z][a-z0-9-]+)", r"##-\1", text)
    text = re.sub(r"\bQ\d+\b", "Q#", text)
    return text.rstrip("\n")


def main():
    errors = []
    dirs = sorted(p for p in KB.iterdir() if p.is_dir() and re.match(r"^\d\d-", p.name))
    docs = sorted(p for d in dirs for p in d.glob("[0-9][0-9]-*.md"))
    if [int(d.name[:2]) for d in dirs] != list(range(1, 16)):
        errors.append("directory prefixes are not 01–15")
    if len(docs) != 134:
        errors.append(f"document count {len(docs)} != 134")
    if len(dirs) != 15:
        errors.append(f"directory count {len(dirs)} != 15")
    total = 0
    for d in dirs:
        files = sorted(d.glob("[0-9][0-9]-*.md"))
        if [int(p.name[:2]) for p in files] != list(range(1, len(files) + 1)):
            errors.append(f"noncontinuous file prefix in {d.name}")
        for p in files:
            ids = list(blocks(p.read_text()))
            total += len(ids)
            if ids != list(range(1, len(ids) + 1)):
                errors.append(f"noncontinuous Q in {p.relative_to(ROOT)}")
    if total != 2291:
        errors.append(f"question count {total} != 2291")

    with (HERE / "mapping.tsv").open() as f:
        rows = list(csv.DictReader(f, delimiter="\t"))
    if len(rows) != 2291:
        errors.append(f"mapping rows {len(rows)} != 2291")
    if len({(r["old_path"], r["old_q"]) for r in rows}) != len(rows):
        errors.append("duplicate old Q in mapping")
    if len({(r["new_path"], r["new_q"]) for r in rows}) != len(rows):
        errors.append("duplicate new Q in mapping")
    if {r["new_path"] for r in rows} != {str(p.relative_to(KB)) for p in docs}:
        errors.append("mapped document set differs from actual files")
    with (HERE / "document-map.tsv").open() as f:
        doc_rows = list(csv.DictReader(f, delimiter="\t"))
    with (HERE / "directory-map.tsv").open() as f:
        dir_rows = list(csv.DictReader(f, delimiter="\t"))
    if len(doc_rows) != 134 or len(dir_rows) != 15:
        errors.append("directory/document mapping counts are incomplete")

    old_cache = {}
    new_cache = {}
    body_errors = []
    for r in rows:
        old_path = r["old_path"]
        new_path = r["new_path"]
        if old_path not in old_cache:
            old_cache[old_path] = blocks(subprocess.check_output(
                ["git", "show", f"HEAD:knowledge-base/01-android/{old_path}"], cwd=ROOT).decode())
        if new_path not in new_cache:
            new_cache[new_path] = blocks((KB / new_path).read_text())
        old_block = old_cache[old_path][int(r["old_q"][1:])]
        new_block = new_cache[new_path][int(r["new_q"][1:])]
        old_title = old_block.splitlines()[0].split(": ", 1)[1].removesuffix("**")
        new_title = new_block.splitlines()[0].split(": ", 1)[1].removesuffix("**")
        if old_title != r["title"] or new_title != r["title"] or normalized(old_block) != normalized(new_block):
            body_errors.append((old_path, r["old_q"], new_path, r["new_q"]))
    if body_errors:
        errors.append(f"title/body mismatches: {len(body_errors)}; first: {body_errors[:5]}")

    readme = (KB / "README.md").read_text()
    sections = re.findall(r"(?m)^## (\d\d-[^/]+)/$", readme)
    if sections != [d.name for d in dirs]:
        errors.append("README section order differs from directory order")
    for phrase in ("Android 主干（01–05）", "系统能力（06–09）", "平台实现（10–12）", "AAOS 集成（13）", "性能专题（14–15）"):
        if phrase not in readme:
            errors.append(f"README route omits {phrase}")
    for p in docs:
        rel = str(p.relative_to(KB))
        if readme.count(f"]({rel})") != 2:
            errors.append(f"README does not list {rel} in both lists")

    audio = (KB / "09-audio/README.md").read_text()
    for n in range(6, 13):
        p = next(KB.glob(f"09-audio/{n:02d}-*.md"))
        if p.name not in audio:
            errors.append(f"audio path omits {p.name}")

    paths = {ROOT / p for p in subprocess.check_output(["git", "ls-files", "-z", "*.md"], cwd=ROOT).decode().split("\0") if p}
    paths.update(docs)
    scoped_links = 0
    broken = []
    for p in paths:
        if not p.exists() or "android-kb-restructure" in str(p) or p.name == "2026-10-03-android-knowledge-base-restructure.md":
            continue
        for number, line in enumerate(p.read_text().splitlines(), 1):
            for target in LINK.findall(line):
                if target.startswith(("<", "/", "#")) or ":" in target.split("/")[0]:
                    continue
                dest = (p.parent / target.split("#", 1)[0]).resolve()
                if KB in p.parents or KB in dest.parents:
                    scoped_links += 1
                    if not dest.exists():
                        broken.append((str(p.relative_to(ROOT)), number, target))
    if broken:
        errors.append(f"broken scoped links: {len(broken)}; first: {broken[:5]}")

    index = (KB / "high-frequency-interview-index.md").read_text()
    stem_doc = {p.stem: p for p in docs}
    explicit_q = 0
    for hit in re.finditer(r"(?<![\w-])(\d\d-[a-z][a-z0-9-]+)\s+Q(\d+)(?:[–-]Q(\d+))?", index):
        stem = hit.group(1)
        start = int(hit.group(2))
        end = int(hit.group(3) or hit.group(2))
        p = stem_doc.get(stem)
        if p is None or start < 1 or start > end or end > len(blocks(p.read_text())):
            errors.append(f"invalid interview index ref {hit.group()}")
        explicit_q += 1

    old_paths = [r["old_path"] for r in doc_rows if r["old_path"] != r["new_path"]]
    stale = []
    for p in paths:
        if not p.exists() or "android-kb-restructure" in str(p) or p.name == "2026-10-03-android-knowledge-base-restructure.md":
            continue
        source = p.read_text()
        stale.extend((str(p.relative_to(ROOT)), old) for old in old_paths if old in source)
    if stale:
        errors.append(f"stale old document paths: {len(stale)}; first: {stale[:5]}")

    print(f"directories={len(dirs)} documents={len(docs)} questions={total} mapped_questions={len(rows)}")
    print(f"mapped_title_body_mismatches={len(body_errors)} scoped_relative_links={scoped_links} broken_links={len(broken)}")
    print(f"README_sections={len(sections)} audio_chain=06–12 index_explicit_Q_refs={explicit_q} stale_paths={len(stale)}")
    if errors:
        for error in errors:
            print("ERROR:", error)
        raise SystemExit(1)
    print("verification=PASS")


if __name__ == "__main__":
    main()

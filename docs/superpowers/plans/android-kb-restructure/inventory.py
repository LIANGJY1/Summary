#!/usr/bin/env python3
"""Task 1 inventory: parse every Q in knowledge-base/01-android using Atlas-compatible rules."""
import hashlib
import json
import os
import re
import sys

ROOT = "/home/liang/Project/MyProject/Summary"
BASE = os.path.join(ROOT, "knowledge-base/01-android")
OUT = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")

BOLD = re.compile(r"^\s*\*\*Q(\d+)\s*[:：]\s*(.*?)\*\*\s*$")
HEADING = re.compile(r"^\s*#{1,6}\s*Q(\d+)\s*[:：]\s*(.*?)\s*$")
PLAIN = re.compile(r"^\s*Q(\d+)\s*[:：]\s*(.*?)\s*$")
STATUS = re.compile(r"^\[([A-Za-z][A-Za-z0-9_-]*)\]\s*")
TAGS = re.compile(r"^\[tags:([^\]\r\n]*)\]\s*", re.IGNORECASE)
SECTION = re.compile(r"^\s*(?:#{1,6}\s+)?第\s*[0-9一二三四五六七八九十百]+\s*[章节]\s+(.+?)\s*$")
KNOWN_STATUS = {"todo", "learning", "done"}

MD_LINK = re.compile(r"\[([^\]]*)\]\(([^)\s]+)\)")


def parse_qs(text):
    """Atlas-compatible: fence-aware Q markers + section boundaries."""
    markers = []  # (number, raw_question, status, tags, line_idx, answer_start_line)
    sections = []  # line indexes
    fenced = False
    for i, line in enumerate(text.split("\n")):
        t = line.lstrip()
        if t.startswith("```") or t.startswith("~~~"):
            fenced = not fenced
            continue
        if fenced:
            continue
        m = None
        for pat in (BOLD, HEADING, PLAIN):
            if pat.match(line):
                m = pat.match(line)
                break
        if m:
            num = int(m.group(1))
            q = m.group(2)
            status, tags = None, None
            for _ in range(2):
                sm = None if status is not None else STATUS.match(q)
                if sm and sm.group(1).lower() in KNOWN_STATUS:
                    status = sm.group(1).lower()
                    q = q[sm.end():]
                else:
                    tm = None if tags is not None else TAGS.match(q)
                    if tm:
                        tags = tm.group(1)
                        q = q[tm.end():]
            markers.append({
                "number": num, "question": q.strip(), "status": status or "todo",
                "tags": tags or "", "line": i,
            })
        elif SECTION.match(line):
            sections.append(i)
    entries = []
    for idx, mk in enumerate(markers):
        start = mk["line"] + 1
        end = markers[idx + 1]["line"] if idx + 1 < len(markers) else len(text.split("\n"))
        nxt_sec = next((s for s in sections if s > mk["line"]), len(text.split("\n")))
        end = min(end, nxt_sec)
        answer = "\n".join(text.split("\n")[start:end]).strip()
        entries.append({**mk, "answer": answer, "answer_len": len(answer)})
    return entries


def main():
    rows = []
    file_meta = []
    for dirpath, dirnames, filenames in sorted(os.walk(BASE)):
        dirnames.sort()
        for fn in sorted(filenames):
            if not fn.endswith(".md"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            with open(path, "rb") as f:
                raw = f.read()
            sha = hashlib.sha256(raw).hexdigest()
            try:
                text = raw.decode("utf-8")
            except UnicodeDecodeError as e:
                file_meta.append({"path": rel, "sha256": sha, "error": f"utf8: {e}"})
                continue
            h1 = next((l.lstrip("# ").strip() for l in text.split("\n") if re.match(r"^#\s+\S", l)), None)
            qs = parse_qs(text)
            file_meta.append({
                "path": rel, "sha256": sha, "h1": h1, "q_count": len(qs),
                "bytes": len(raw),
            })
            for q in qs:
                body_hash = hashlib.sha256(q["answer"].encode("utf-8")).hexdigest()[:16]
                rows.append({
                    "old_path": rel.replace("knowledge-base/01-android/", ""),
                    "old_q": q["number"],
                    "old_title": q["question"],
                    "old_status": q["status"],
                    "old_tags": q["tags"],
                    "answer_len": q["answer_len"],
                    "body_sha16": body_hash,
                })
    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, "inventory.json"), "w", encoding="utf-8") as f:
        json.dump({"files": file_meta, "questions": rows}, f, ensure_ascii=False, indent=1)
    # summary
    total = len(rows)
    empty = [r for r in rows if r["answer_len"] == 0]
    print(f"files={len(file_meta)} questions={total} empty_answers={len(empty)}")
    for e in empty:
        print(f"  EMPTY: {e['old_path']}#Q{e['old_q']} {e['old_title'][:60]}")
    # non-sequential / duplicate numbering per file
    from collections import defaultdict
    per_file = defaultdict(list)
    for r in rows:
        per_file[r["old_path"]].append(r["old_q"])
    for path, nums in per_file.items():
        expected = list(range(1, len(nums) + 1))
        if nums != expected:
            print(f"  NONSEQ/DUP: {path} -> {nums[:30]}{'...' if len(nums) > 30 else ''}")
    # h1 missing
    for fm in file_meta:
        if not fm.get("h1"):
            print(f"  NO_H1: {fm['path']}")


if __name__ == "__main__":
    main()

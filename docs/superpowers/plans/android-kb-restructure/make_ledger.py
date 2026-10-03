#!/usr/bin/env python3
"""Generate question-ledger.tsv skeleton + baseline.md from inventory.json (Task 1 output)."""
import hashlib
import json
import os
import re
from collections import defaultdict

ROOT = "/home/liang/Project/MyProject/Summary"
BASE = os.path.join(ROOT, "knowledge-base/01-android")
OUT = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")

BOLD = re.compile(r"^\s*\*\*Q(\d+)\s*[:：]\s*(.*?)\*\*\s*$")
HEADING = re.compile(r"^\s*#{1,6}\s*Q(\d+)\s*[:：]\s*(.*?)\s*$")
PLAIN = re.compile(r"^\s*Q(\d+)\s*[:：]\s*(.*?)\s*$")
STATUS = re.compile(r"^\[([A-Za-z][A-Za-z0-9_-]*)\]\s*")
TAGS = re.compile(r"^\[tags:([^\]\r\n]*)\]\s*", re.IGNORECASE)
KNOWN_STATUS = {"todo", "learning", "done"}


def parse_qs(text):
    markers, sections = [], []
    fenced = False
    lines = text.split("\n")
    for i, line in enumerate(lines):
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
            markers.append({"number": num, "question": q.strip(), "status": status or "todo",
                            "tags": tags or "", "line": i})
        elif re.match(r"^\s*(?:#{1,6}\s+)?第\s*[0-9一二三四五六七八九十百]+\s*[章节]\s+", line):
            sections.append(i)
    entries = []
    for idx, mk in enumerate(markers):
        start = mk["line"] + 1
        end = markers[idx + 1]["line"] if idx + 1 < len(markers) else len(lines)
        nxt_sec = next((s for s in sections if s > mk["line"]), len(lines))
        end = min(end, nxt_sec)
        answer = "\n".join(lines[start:end]).strip()
        entries.append({**mk, "answer": answer})
    return entries


def esc(s):
    return s.replace("\t", " ").replace("\n", " ").strip()


def main():
    ledger_path = os.path.join(OUT, "question-ledger.tsv")
    rows = []
    file_meta = []
    for dirpath, dirnames, filenames in sorted(os.walk(BASE)):
        dirnames.sort()
        for fn in sorted(filenames):
            if not fn.endswith(".md"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            raw = open(path, "rb").read()
            sha = hashlib.sha256(raw).hexdigest()
            text = raw.decode("utf-8")
            h1 = next((l.lstrip("# ").strip() for l in text.split("\n") if re.match(r"^#\s+\S", l)), None)
            qs = parse_qs(text)
            file_meta.append({"path": rel, "sha256": sha, "h1": h1, "q_count": len(qs), "bytes": len(raw)})
            for q in qs:
                body_hash = hashlib.sha256(q["answer"].encode("utf-8")).hexdigest()
                rows.append([
                    rel, f"Q{q['number']}", esc(q["question"]), q["status"], esc(q["tags"]),
                    body_hash, esc(q["question"]),  # core_question placeholder = title
                    "", "", "", "", "", "executor", "pending",
                ])
    cols = ["old_path", "old_q", "old_title", "old_status", "old_tags", "old_body_sha256",
            "core_question", "action", "new_path", "new_q", "new_title",
            "evidence_version", "related_old_ids", "reviewer", "review_state"]
    with open(ledger_path, "w", encoding="utf-8") as f:
        f.write("\t".join(cols) + "\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    # path-map skeleton
    with open(os.path.join(OUT, "path-map.tsv"), "w", encoding="utf-8") as f:
        f.write("old_path\tnew_path\treason\taffected_links_checked\n")
    # baseline.md
    per_dir = defaultdict(int)
    q_per_dir = defaultdict(int)
    for fm in file_meta:
        d = fm["path"].split("/")[1]
        per_dir[d] += 1
        q_per_dir[d] += fm["q_count"]
    with open(os.path.join(OUT, "file-baseline.json"), "w", encoding="utf-8") as f:
        json.dump(file_meta, f, ensure_ascii=False, indent=1)
    print(f"ledger rows: {len(rows)}, files: {len(file_meta)}")
    for d in sorted(per_dir):
        print(f"  {d}: {per_dir[d]} files, {q_per_dir[d]} Qs")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Task 9 acceptance: structure + Atlas-parse + provenance coverage."""
import csv, hashlib, os, re, subprocess, sys
from collections import Counter, defaultdict

ROOT = "/home/liang/Project/MyProject/Summary"
BASE = os.path.join(ROOT, "knowledge-base/01-android")
OUTDIR = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")

BOLD = re.compile(r"^\s*\*\*Q(\d+)\s*[:：]\s*(.*?)\*\*\s*$")
HEADING = re.compile(r"^\s*#{1,6}\s*Q(\d+)\s*[:：]\s*(.*?)\s*$")
PLAIN = re.compile(r"^\s*Q(\d+)\s*[:：]\s*(.*?)\s*$")
STATUS = re.compile(r"^\[([A-Za-z][A-Za-z0-9_-]*)\]\s*")
TAGS = re.compile(r"^\[tags:([^\]\r\n]*)\]\s*", re.I)
KNOWN = {"todo", "learning", "done"}
MDLINK = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")

def parse_doc(text):
    lines = text.split("\n")
    fenced = False
    qs = []
    h1 = None
    for i, line in enumerate(lines):
        t = line.lstrip()
        if t.startswith("```") or t.startswith("~~~"):
            fenced = not fenced
            continue
        if fenced:
            continue
        if h1 is None and re.match(r"^#\s+\S", line):
            h1 = line.lstrip("# ").strip()
        m = None
        for pat in (BOLD, HEADING, PLAIN):
            mm = pat.match(line)
            if mm:
                m = mm
                break
        if m:
            num = int(m.group(1))
            q = m.group(2)
            status, tags = "todo", ""
            for _ in range(2):
                sm = STATUS.match(q)
                if sm and sm.group(1).lower() in KNOWN:
                    status = sm.group(1).lower(); q = q[sm.end():]
                else:
                    tm = TAGS.match(q)
                    if tm:
                        tags = tm.group(1); q = q[tm.end():]
            qs.append({"num": num, "title": q.strip(), "status": status, "tags": tags, "line": i})
    for idx, q in enumerate(qs):
        start = q["line"] + 1
        end = qs[idx + 1]["line"] if idx + 1 < len(qs) else len(lines)
        q["answer"] = "\n".join(lines[start:end]).strip()
    return h1, qs

def norm(s):
    s = MDLINK.sub(r"[\1](L)", s)
    s = re.sub(r"Q\d+", "Q#", s)
    s = re.sub(r"[0-9A-Za-z][0-9A-Za-z\-]*\.md", "S", s)
    s = re.sub(r"[01][0-9]-[\u4e00-\u9fffA-Za-z]+(?:与[\u4e00-\u9fffA-Za-z]+)*\.md", "S", s)
    s = re.sub(r"[0-9A-Za-z][0-9A-Za-z\-]{3,40}(?= ?Q#)", "S", s)
    s = re.sub(r"[01][0-9]-[\u4e00-\u9fffA-Za-z]+(?:与[\u4e00-\u9fffA-Za-z]+)*(?= ?Q#)", "S", s)
    s = re.sub(r"启动册|架构册|内核册|存储册", "S", s)
    s = re.sub(r"补充（设备面与 AAOS 语境，自原 11 册同题合并）：", "", s)
    s = re.sub(r"\s+", "", s)
    return s

errors = []
warns = []
new_index = defaultdict(list)   # (path, num) -> answer
norm_index = defaultdict(list)  # normhash -> [(path, num)]
all_paths = []
for dp, dn, fn in os.walk(BASE):
    for f in sorted(fn):
        if not f.endswith(".md"):
            continue
        p = os.path.join(dp, f)
        rel = os.path.relpath(p, BASE)
        all_paths.append(rel)
        raw = open(p, "rb").read()
        try:
            text = raw.decode("utf-8")
        except UnicodeDecodeError:
            errors.append(f"UTF8: {rel}")
            continue
        if rel == "README.md":
            continue
        if f not in ("README.md",) and not re.match(r"^\d{2}-[a-z0-9\-]+\.md$", f) and rel != "面试高频索引.md" and rel != "13-audio/README.md":
            errors.append(f"NAME: {rel}")
        h1, qs = parse_doc(text)
        if h1 is None:
            errors.append(f"NO_H1: {rel}")
        _fenced = False
        h1_count = 0
        for _line in text.split("\n"):
            _t = _line.lstrip()
            if _t.startswith("```") or _t.startswith("~~~"):
                _fenced = not _fenced
                continue
            if not _fenced and re.match(r"^#\s+\S", _line):
                h1_count += 1
        if h1_count > 1:
            errors.append(f"MULTI_H1: {rel} x{h1_count}")
        nums = [q["num"] for q in qs]
        if nums != list(range(1, len(nums) + 1)):
            errors.append(f"NUM: {rel} {nums[:10]}")
        for q in qs:
            if not q["answer"].strip():
                errors.append(f"EMPTY: {rel}#Q{q['num']}")
            key = (rel, q["num"])
            new_index[key] = q["answer"]
            h = hashlib.sha256(norm(q["answer"]).encode()).hexdigest()[:16]
            norm_index[h].append(key)
# unfenced stray Q markers already excluded by fence handling; check fenced blocks containing **Qn
for dp, dn, fn in os.walk(BASE):
    for f in fn:
        if not f.endswith(".md"):
            continue
        p = os.path.join(dp, f)
        rel = os.path.relpath(p, BASE)
        text = open(p, encoding="utf-8").read()
        fenced = False
        for line in text.split("\n"):
            t = line.lstrip()
            if t.startswith("```") or t.startswith("~~~"):
                fenced = not fenced
                continue
            if fenced and re.match(r"^\s*\*\*Q\d+", line):
                errors.append(f"FENCED-Q: {rel}: {line[:60]}")

for outp, rel_key in [(os.path.join(ROOT,"knowledge-base/04-exp/01-提交治理与防回归.md"),"OUT:knowledge-base/04-exp/01-提交治理与防回归.md"),
                      (os.path.join(ROOT,"knowledge-base/网络/01-network-fundamentals.md"),"OUT:knowledge-base/网络/01-network-fundamentals.md"),
                      (os.path.join(ROOT,"knowledge-base/career/work-project-analysis/01-launcher-vehicle-service.md"),"OUT:knowledge-base/career/work-project-analysis/01-launcher-vehicle-service.md")]:
    h1, qs = parse_doc(open(outp, encoding="utf-8").read())
    for q in qs:
        new_index[(rel_key, q["num"])] = q["answer"]
        h = hashlib.sha256(norm(q["answer"]).encode()).hexdigest()[:16]
        norm_index[h].append((rel_key, q["num"]))

# provenance: every old answer (from HEAD) normalized must exist in exactly one new location
def head(rel):
    r = subprocess.run(["git", "show", f"HEAD:knowledge-base/01-android/{rel}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None

ledger = {}
for r in csv.DictReader(open(os.path.join(OUTDIR, "question-ledger.tsv")), delimiter="\t"):
    ledger[(r["old_path"], int(r["old_q"][1:]))] = r

missing, dupnorm = [], []
for (op, oq), row in sorted(ledger.items()):
    if row["action"] in ("remove-duplicate",):
        continue
    t = head(op)
    if t is None:
        warns.append(f"no HEAD text: {op}")
        continue
    _, qs = parse_doc(t)
    ob = next((q["answer"] for q in qs if q["num"] == oq), None)
    if ob is None:
        errors.append(f"ledger src missing in HEAD: {op}#Q{oq}")
        continue
    if row["action"] == "merge":
        continue  # merged body folded into another answer; checked manually
    h = hashlib.sha256(norm(ob).encode()).hexdigest()[:16]
    hits = norm_index.get(h, [])
    if not hits:
        missing.append((op, oq, row["new_path"], row["new_q"]))
    elif len(hits) > 1:
        dupnorm.append((op, oq, hits))

print(f"structure errors: {len(errors)}")
for e in errors[:40]:
    print("  E:", e)
print(f"provenance missing: {len(missing)}")
for m in missing[:30]:
    print("  M:", m)
print(f"dup-normalized: {len(dupnorm)}")
for d in dupnorm[:10]:
    print("  D:", d)
print(f"warns: {len(warns)}")
for w in warns[:5]:
    print("  W:", w)

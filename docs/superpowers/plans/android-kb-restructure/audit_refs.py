#!/usr/bin/env python3
"""Audit reference rewrites: compare new answers vs git HEAD originals; flag inserted stems whose original was a bare Q ref."""
import csv, os, re, subprocess, sys

ROOT = "/home/liang/Project/MyProject/Summary"
BASE = os.path.join(ROOT, "knowledge-base/01-android")
OUTDIR = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")

BOLD = re.compile(r"^(\s*)\*\*Q(\d+)(:\s.*)\*\*\s*$")
HEADING = re.compile(r"^(\s*)#{1,6}\s*Q(\d+)(:\s.*)$")
PLAIN = re.compile(r"^(\s*)Q(\d+)(:\s.*)$")

def parse(path_or_text, from_head=False):
    text = path_or_text
    lines = text.split("\n")
    fenced = False
    qstarts = []
    for i, line in enumerate(lines):
        t = line.lstrip()
        if t.startswith("```") or t.startswith("~~~"):
            fenced = not fenced
            continue
        if fenced:
            continue
        for pat in (BOLD, HEADING, PLAIN):
            m = pat.match(line)
            if m:
                qstarts.append((i, int(m.group(2))))
                break
    blocks = {}
    for idx, (start, num) in enumerate(qstarts):
        end = qstarts[idx + 1][0] if idx + 1 < len(qstarts) else len(lines)
        blocks[num] = "\n".join(lines[start + 1 : end])
    return blocks

def head_text(rel):
    r = subprocess.run(["git", "show", f"HEAD:knowledge-base/01-android/{rel}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None

ledger = {}
for r in csv.DictReader(open(os.path.join(OUTDIR, "question-ledger.tsv")), delimiter="\t"):
    if r["action"] in ("move", "keep", "merge"):
        ledger[(r["old_path"], int(r["old_q"][1:]))] = (r["new_path"], int(r["new_q"][1:]))

# group old->new by new file
by_new = {}
for (src, q), (dst, nq) in ledger.items():
    by_new.setdefault(dst, {}).setdefault(src, {})[nq] = q

# load originals
orig_blocks = {}
for src in {s for s, _ in ledger}:
    t = head_text(src)
    if t is None:
        print(f"!! no HEAD text for {src}")
        continue
    orig_blocks[src] = parse(t)

# new stems set


import types
mig_src = open(os.path.join(OUTDIR, "migrate.py")).read()
ns = {"__file__": os.path.join(OUTDIR, "migrate.py")}
exec(compile(mig_src.split("def parse_file")[0], "migrate_head", "exec"), ns)
PURE_RENAMES, REP = ns["PURE_RENAMES"], ns["REP"]
new_stems = set()
for s, d in PURE_RENAMES.items():
    if s != d:
        new_stems.add(os.path.basename(d)[:-3])
for s, d in REP.items():
    new_stems.add(os.path.basename(d.replace("OUT:", ""))[:-3])

stem_q = re.compile(r"([0-9A-Za-z][0-9A-Za-z\-]{" + "3,40}) (Q\d+)")

issues = []
for dst, srcmap in by_new.items():
    newp = os.path.join(BASE, dst)
    if not os.path.exists(newp):
        continue
    ntext = open(newp, encoding="utf-8").read()
    nlines = ntext.split("\n")
    # split new file into blocks by Q marker
    fenced = False
    qstarts = []
    for i, line in enumerate(nlines):
        t = line.lstrip()
        if t.startswith("```") or t.startswith("~~~"):
            fenced = not fenced
            continue
        if fenced:
            continue
        for pat in (BOLD, HEADING, PLAIN):
            m = pat.match(line)
            if m:
                qstarts.append((i, int(m.group(2))))
                break
    for idx, (start, nq) in enumerate(qstarts):
        end = qstarts[idx + 1][0] if idx + 1 < len(qstarts) else len(nlines)
        owner = None
        for s, m_ in srcmap.items():
            if nq in m_:
                owner = (s, m_[nq])
                break
        if owner is None:
            continue
        src, oq = owner
        if src not in orig_blocks or oq not in orig_blocks[src]:
            continue
        old_ans = orig_blocks[src][oq]
        new_ans = "\n".join(nlines[start + 1 : end])
        if old_ans == new_ans:
            continue
        # find inserted "stem Qn" in new text; check what stood there in old
        for m in stem_q.finditer(new_ans):
            stem, qnew = m.group(1), m.group(2)
            if stem not in new_stems:
                continue
            pre = new_ans[max(0, m.start() - 25):m.start()]
            post = new_ans[m.end():m.end() + 25]
            pre_m = re.escape(pre)
            post_m = re.escape(post)
            # mask patterns in old: bare Q / oldstem Q
            mm = re.search(pre_m + r"([0-9A-Za-z][0-9A-Za-z\-]{0,40} )?(Q\d+)" + post_m, old_ans)
            if mm is None:
                issues.append(("NO-ALIGN", dst, nq, src, oq, m.group(0), pre[-20:], post[:20]))
            elif mm.group(1):
                pass  # original had a stem too -> renamed correctly
            else:
                issues.append(("BARE->STEM", dst, nq, src, oq, m.group(0), pre[-25:], post[:25]))

for it in issues:
    print(" | ".join(str(x) for x in it))
print("issues:", len(issues))

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
# R7 自验正规口径（台账§六复核指南E）：剥含CJK(\u4e00-\u9fff/\u3000-\u303f/\uff00-\uffef)行
# 后 difflib SequenceMatcher，非 insert opcode（delete/replace）必须为 0
import subprocess, difflib, os, sys
PRISTINE = "/home/liang/Project/AAOS13"
STUDY = "/home/liang/Project/MyProject/AAOS13_study"
def has_cjk(line):
    for ch in line:
        o = ord(ch)
        if 0x4e00 <= o <= 0x9fff or 0x3000 <= o <= 0x303f or 0xff00 <= o <= 0xffef:
            return True
    return False
def stripped(path):
    with open(path, encoding="utf-8", errors="replace") as fh:
        return [ln for ln in fh.read().splitlines() if not has_cjk(ln)]
files = subprocess.check_output(
    ["git", "status", "--porcelain"], cwd=STUDY, text=True)
targets = []
for ln in files.splitlines():
    p = ln[3:].strip()
    if p.endswith((".java", ".cpp", ".c", ".h", ".cc")) and not p.startswith("docs/"):
        targets.append(p)
total = 0; drift = 0; details = []
for f in sorted(targets):
    total += 1
    pa = os.path.join(PRISTINE, f); sa = os.path.join(STUDY, f)
    if not os.path.isfile(pa):
        details.append(f"MISSING-PRISTINE {f}"); drift += 1; continue
    a = stripped(pa); b = stripped(sa)
    sm = difflib.SequenceMatcher(a=a, b=b, autojunk=False)
    noninsert = [(tag, i1, i2, j1, j2) for tag, i1, i2, j1, j2 in sm.get_opcodes()
                 if tag != "insert" and tag != "equal"]
    n_ins = sum(1 for tag, *_ in sm.get_opcodes() if tag == "insert")
    if noninsert:
        drift += 1
        details.append(f"DRIFT {f}: noninsert={noninsert[:5]}")
    else:
        details.append(f"OK {f}: noninsert=0 insert_blocks={n_ins}")
print("\n".join(details))
print(f"\n==== CANONICAL TOTAL files={total} drift_files={drift} ====")
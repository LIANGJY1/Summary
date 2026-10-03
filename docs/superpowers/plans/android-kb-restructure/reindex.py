#!/usr/bin/env python3
"""Rewrite 面试高频索引.md references through the migration ledger."""
import csv, os, re

ROOT = "/home/liang/Project/MyProject/Summary"
OUTDIR = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")
IDX = os.path.join(ROOT, "knowledge-base/01-android/面试高频索引.md")

SH = {
    "01-架构": "01-architecture/01-Android系统架构.md",
    "02-启动": "01-architecture/02-Android系统启动流程.md",
    "03-Binder": "01-architecture/03-Binder.md",
    "04-Sanbox": "01-architecture/04-Sanbox.md",
    "05-Art": "01-architecture/05-Art.md",
    "06-JNI": "01-architecture/06-JNI.md",
    "07-分区": "01-architecture/07-Android分区.md",
    "08-SystemServer": "01-architecture/08-SystemServer.md",
    "09-HAL": "01-architecture/09-HAL.md",
    "10-Kernel": "01-architecture/10-Kernel.md",
    "12-类加载": "01-architecture/12-类加载ART编译与JNI链接.md",
    "13 册": "01-architecture/13-MessageQueue锁竞争与Binder深化.md",
    "13-MessageQueue锁竞争与Binder深化": "01-architecture/13-MessageQueue锁竞争与Binder深化.md",
    "14-系统服务调度核心": "01-architecture/14-系统服务调度核心.md",
    "15-安装归档与资源配置": "01-architecture/15-安装归档与资源配置.md",
    "16-显示与窗口链路": "01-architecture/16-显示与窗口链路.md",
    "17-Telephony与Connectivity": "01-architecture/17-Telephony与Connectivity.md",
    "18-Notification-Biometric-Location": "01-architecture/18-Notification-Biometric-Location.md",
    "19-AVF": "01-architecture/19-AVF可观测与AI手机技术栈.md",
    "01-architecture/19": "01-architecture/19-AVF可观测与AI手机技术栈.md",
    "20-SELinux": "01-architecture/20-SELinux.md",
    "01-architecture/16": "01-architecture/16-显示与窗口链路.md",
    "01-architecture/14-系统服务调度核心": "01-architecture/14-系统服务调度核心.md",
    "07-渲染管线-基础": "07-performance/07-渲染管线-基础与图形API.md",
    "06-system/01": "06-system/01-AOSP性能优化.md",
    "06-system/02": "06-system/02-OEM与设备差异.md",
    "06-system/03": "06-system/03-CarService服务速览.md",
    "10-网络分层原理与地基机制": "14-network/10-网络分层原理与地基机制.md",
    "09/03": "09-app-practice/03-稳定性治理-线程与IPC.md",
    "09/05": "09-app-practice/05-启动优化.md",
    "09/13": "09-app-practice/13-功耗优化实践.md",
    "09/02": "09-app-practice/02-稳定性治理-资源泄漏.md",
}

ledger = {}
for r in csv.DictReader(open(os.path.join(OUTDIR, "question-ledger.tsv")), delimiter="\t"):
    if r["action"] in ("move", "keep", "merge"):
        ledger[(r["old_path"], int(r["old_q"][1:]))] = (r["new_path"], int(r["new_q"][1:]))

def group(refs):
    """refs: list of (new_path, new_q). Group contiguous ranges per path, in first-seen order."""
    out = []
    for p, q in refs:
        if out and out[-1][0] == p and q == out[-1][-1][1] + 1:
            out[-1].append((p, q))
        else:
            out.append([(p, q)])
    parts = []
    for grp in out:
        p = grp[0][0]
        qs = [q for _, q in grp]
        # compress contiguous
        segs = []
        s = qs[0]; prev = qs[0]
        for q in qs[1:]:
            if q == prev + 1:
                prev = q
            else:
                segs.append((s, prev)); s = prev = q
        segs.append((s, prev))
        qtxt = "、".join(f"Q{a}" if a == b else (f"Q{a}–Q{b}" if b > a else f"Q{a}") for a, b in segs)
        parts.append((p, qtxt))
    return parts

def stem_for(path):
    return os.path.basename(path)[:-3]

def map_q(old_path, n):
    return ledger.get((old_path, n))

text = open(IDX, encoding="utf-8").read()

# process token: (SH key or path stem) followed by Q-list like "Q1、Q6" "Q1–Q9" "Q19–Q23、Q28" "Q41" "Q15–Q19"
# We walk line by line; for each known stem occurrence, take the trailing Q tokens until a non-Q token.
TOKEN = re.compile(r"(Q\d+(?:[–—-]Q\d+)?)([、/，]?)")
lines_out = []
for line in text.split("\n"):
    # find all stem candidates and rewrite their trailing Q lists
    # sort keys by length desc to avoid partial matches
    changed = line
    for k in sorted(SH, key=len, reverse=True):
        while True:
            idx = changed.find(k)
            if idx < 0:
                break
            # ensure not already processed (followed by Q tokens handled below)
            rest = changed[idx + len(k):]
            m = re.match(r"(\s*(?:Q\d+(?:[–—-]Q\d+)?[、/，]?\s*)+)", rest)
            if not m:
                # stem with no Q list: leave, but record for path rename only
                newp_map = {"01-architecture/19": "01-architecture/19-AVF可观测与AI手机技术栈.md"}.get(k, SH.get(k))
                if newp_map and rest.startswith(("、", "；", "）", "，", "。", "；", " ", "")):
                    newp = None
                    import csv as _c
                    # map whole-doc mention: use majority destination
                    cnt = {}
                    for (op, oq), (np_, nq) in ledger.items():
                        if op == newp_map:
                            cnt[np_] = cnt.get(np_, 0) + 1
                    if cnt:
                        newp = max(cnt, key=cnt.get)
                        changed = changed[:idx] + stem_for(newp) + changed[idx + len(k):]
                        continue
                break
            qlist = m.group(1)
            oldp = SH[k]
            refs = []
            bad = []
            for qm in re.finditer(r"Q(\d+)(?:[–—-]Q(\d+))?", qlist):
                a = int(qm.group(1)); b = int(qm.group(2)) if qm.group(2) else a
                for n in range(a, b + 1):
                    r = map_q(oldp, n)
                    if r is None:
                        bad.append(n)
                    else:
                        refs.append(r)
            if not refs:
                break
            parts = group(refs)
            # emit: first part replaces stem+qlist; subsequent parts appended with own stems
            first_stem = stem_for(parts[0][0])
            rep = first_stem + " " + parts[0][1]
            for p, qt in parts[1:]:
                rep += "；" + stem_for(p) + " " + qt
            if bad:
                rep += f"（原Q{'、Q'.join(map(str, bad))}待核）"
            changed = changed[:idx] + rep + changed[idx + len(k) + len(m.group(1)):]
            break
    lines_out.append(changed)

open(IDX + ".new", "w", encoding="utf-8").write("\n".join(lines_out))
print("written .new; sample:")
for l in lines_out[6:12]:
    print(" ", l[:150])

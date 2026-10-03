#!/usr/bin/env python3
"""Execute the 01-android restructure per spec.py. Run from repo root."""
import hashlib
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from spec import PURE_RENAMES, ORDER, MERGE_INTO

ROOT = "/home/liang/Project/MyProject/Summary"
BASE = os.path.join(ROOT, "knowledge-base/01-android")
OUTDIR = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")
KB = os.path.join(ROOT, "knowledge-base")

BOLD = re.compile(r"^(\s*)\*\*Q(\d+)(:\s.*)\*\*\s*$")
HEADING = re.compile(r"^(\s*)#{1,6}\s*Q(\d+)(:\s.*)$")
PLAIN = re.compile(r"^(\s*)Q(\d+)(:\s.*)$")
MDLINK = re.compile(r"\[([^\]]*)\]\(([^)]+)\)")

# representative destination for dissolved docs (link & prose rewriting)
REP = {
    "01-architecture/10-Kernel.md": "12-platform-native/01-kernel-gki.md",
    "01-architecture/07-Android分区.md": "04-storage/02-partitions.md",
    "01-architecture/22-四大组件.md": "16-app-framework/01-four-components.md",
    "01-architecture/23-Handler消息机制.md": "16-app-framework/02-handler-looper.md",
    "06-system/03-CarService服务速览.md": "17-aaos/01-car-services.md",
    "17-car-app/01-Launcher.md": "17-aaos/02-car-launcher.md",
    "18-build-system/04-linux-kernel-drivers.md": "12-platform-native/05-driver-runtime.md",
    "12-platform-native/03-aconfig特性开关.md": "18-build-system/07-aconfig.md",
    "12-platform-native/01-内核与原生层.md": "12-platform-native/03-shared-memory.md",
    "01-architecture/11-版本演进与图形栈预加载.md": "02-rendering/05-graphic-stack-preload.md",
    "01-architecture/12-类加载ART编译与JNI链接.md": "01-architecture/05-art-runtime.md",
    "01-architecture/13-MessageQueue锁竞争与Binder深化.md": "01-architecture/03-binder.md",
    "01-architecture/14-系统服务调度核心.md": "06-platform-services/07-broadcast.md",
    "01-architecture/15-安装归档与资源配置.md": "01-architecture/15-package-management.md",
    "01-architecture/16-显示与窗口链路.md": "15-ui/05-window-system.md",
    "01-architecture/17-Telephony与Connectivity.md": "14-network/04-cellular-wireless.md",
    "01-architecture/18-Notification-Biometric-Location.md": "06-platform-services/01-notifications.md",
    "01-architecture/19-AVF可观测与AI手机技术栈.md": "12-platform-native/07-bpf.md",
    "06-system/01-AOSP性能优化.md": "07-performance/09-platform-optimization.md",
    "06-system/02-OEM与设备差异.md": "08-cpu-power/01-scheduler-power-framework.md",
    "07-performance/07-渲染管线-基础与图形API.md": "02-rendering/04-graphics-api.md",
    "07-performance/08-渲染管线-跨框架与媒体.md": "09-app-practice/09-rendering-media-hybrid.md",
    "09-app-practice/18-应用开发机制与常用API.md": "16-app-framework/04-parcel.md",
    "14-network/10-网络分层原理与地基机制.md": "OUT:knowledge-base/网络/01-network-fundamentals.md",
    "11-defects/08-提交治理与防回归.md": "OUT:knowledge-base/04-exp/01-提交治理与防回归.md",
    "16-project-architecture/01-应用进程启动与全局服务生命周期.md": "OUT:knowledge-base/career/work-project-analysis/01-launcher-vehicle-service.md",
}
SKIP_COVERAGE = {("17-car-app/01-Launcher.md", 2), ("18-build-system/02-soong-modules.md", 10), ("13-audio/11-playback-hal.md", 2)}

# 01-arch Q3 moves to 17-aaos/05 (added to ORDER via spec; expressed here for ledger only)
EXTRA_MOVES = [("01-architecture/01-Android系统架构.md", 3, "17-aaos/05-vehicle-links.md")]

def parse_file(path):
    text = open(path, encoding="utf-8").read()
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
    h1 = None
    pre_end = qstarts[0][0] if qstarts else len(lines)
    for i in range(pre_end):
        if re.match(r"^#\s+\S", lines[i]):
            h1 = lines[i].lstrip("# ").strip()
            break
    preamble = "\n".join(lines[: qstarts[0][0]]).rstrip() if qstarts else ""
    blocks = {}
    for idx, (start, num) in enumerate(qstarts):
        end = qstarts[idx + 1][0] if idx + 1 < len(qstarts) else len(lines)
        blocks[num] = {"marker": lines[start], "answer": "\n".join(lines[start + 1 : end]).strip("\n")}
    return {"h1": h1, "preamble": preamble, "blocks": blocks}

def parse_all():
    docs = {}
    for dirpath, dirnames, filenames in os.walk(BASE):
        dirnames.sort()
        for fn in sorted(filenames):
            if fn.endswith(".md"):
                rel = os.path.relpath(os.path.join(dirpath, fn), BASE)
                docs[rel] = parse_file(os.path.join(dirpath, fn))
    return docs

def main(apply=True):
    from migrate_preambles import P
    docs = parse_all()
    print(f"parsed {len(docs)} source docs")

    order = {}
    for src, dst in PURE_RENAMES.items():
        order[dst] = [(src, q) for q in sorted(docs[src]["blocks"])]
    bs1 = sorted(docs["18-build-system/01-product-config.md"]["blocks"])
    order["18-build-system/01-product-config.md"] = [("18-build-system/01-product-config.md", q) for q in bs1] + [("06-system/01-AOSP性能优化.md", 5)]
    bs2 = [q for q in sorted(docs["18-build-system/02-soong-modules.md"]["blocks"]) if q != 10]
    order["18-build-system/02-soong-modules.md"] = [("18-build-system/02-soong-modules.md", q) for q in bs2] + [("06-system/01-AOSP性能优化.md", q) for q in (6, 12, 13)]
    bs3 = sorted(docs["18-build-system/03-android-kernel-build.md"]["blocks"])
    order["18-build-system/03-android-kernel-build.md"] = [("18-build-system/03-android-kernel-build.md", q) for q in bs3] + [("06-system/01-AOSP性能优化.md", q) for q in (7, 14)]
    for dst, lst in ORDER.items():
        if lst is not None:
            order[dst] = lst
    # identity orders for untouched docs (13-audio/06-12, 15-ui/*, ...)
    used_srcs = {src for lst in order.values() for (src, _) in lst}
    for rel, doc in docs.items():
        if rel not in used_srcs and doc["blocks"]:
            order[rel] = [(rel, q) for q in sorted(doc["blocks"])]

    covered = {}
    for dst, lst in order.items():
        for (src, q) in lst:
            key = (src, q)
            if key in covered:
                sys.exit(f"DUP coverage: {src}#Q{q} -> {covered[key]} and {dst}")
            covered[key] = dst
            if src not in docs or q not in docs[src]["blocks"]:
                sys.exit(f"MISSING source block: {src}#Q{q} (dest {dst})")
    # auto-append leftover blocks of partially-listed sources to their majority destination
    from collections import Counter
    leftover = {}
    for src, doc in docs.items():
        miss = [q for q in sorted(doc["blocks"]) if (src, q) not in covered and (src, q) not in SKIP_COVERAGE]
        if miss:
            cnt = Counter(dst for (s, _), dst in covered.items() if s == src)
            if not cnt:
                sys.exit(f"UNCOVERED with no majority dest: {src} {miss}")
            maj = cnt.most_common(1)[0][0]
            leftover.setdefault(maj, []).extend((src, q) for q in miss)
            print(f"auto-append: {src} {miss} -> {maj}")
    for dst, items in leftover.items():
        lst = order[dst]
        for (src, q) in items:
            if src == dst:
                pos = next((i + 1 for i, (s, qq) in enumerate(lst) if s == src and qq < q), len(lst))
                lst.insert(pos, (src, q))
            else:
                lst.append((src, q))
        order[dst] = lst
        for (src, q) in items:
            covered[(src, q)] = dst
    for src, doc in docs.items():
        for q in doc["blocks"]:
            if (src, q) not in covered and (src, q) not in SKIP_COVERAGE:
                sys.exit(f"UNCOVERED: {src}#Q{q}")
    print("coverage OK:", len(covered), "questions")

    newnum = {}
    for dst, lst in order.items():
        for i, (src, q) in enumerate(lst):
            newnum[(src, q)] = (dst, i + 1)

    # doc lookup by basename stem (old docs + renamed)
    doc_of_stem = {}
    for oldp in docs:
        doc_of_stem[os.path.basename(oldp)[:-3]] = oldp
        if oldp in PURE_RENAMES:
            doc_of_stem[os.path.basename(PURE_RENAMES[oldp])[:-3]] = oldp
    for oldp, newp in REP.items():
        doc_of_stem.setdefault(os.path.basename(oldp)[:-3], oldp)
        doc_of_stem.setdefault(os.path.basename(newp.replace("OUT:", ""))[:-3], oldp)

    ALIAS = {
        "启动册": "01-architecture/02-Android系统启动流程.md",
        "架构册": "01-architecture/01-Android系统架构.md",
        "内核册": "01-architecture/10-Kernel.md",
        "存储册": "04-storage/01-存储与IO.md",
        "memory 册": "05-memory/01-内存管理与压力治理.md",
    }

    def map_doc(oldp):
        if oldp in PURE_RENAMES:
            return PURE_RENAMES[oldp]
        if oldp in REP:
            return REP[oldp]
        return oldp  # unchanged content doc (e.g. 15-ui files)

    def rel_link(from_new_rel, to_map):
        """compute markdown-relative target from new file dir to mapped doc"""
        if to_map.startswith("OUT:"):
            to_abs = os.path.join(ROOT, to_map[4:])
            base_dir = os.path.dirname(os.path.join(BASE, from_new_rel))
            return os.path.relpath(to_abs, base_dir)
        base_dir = os.path.dirname(os.path.join(BASE, from_new_rel))
        return os.path.relpath(os.path.join(BASE, to_map), base_dir)

    def rewrite(text, src_doc, cur_dest):
        # markdown links first (path-aware)
        def link_sub(m):
            label, tgt = m.group(1), m.group(2)
            if tgt.startswith(("http://", "https://", "mailto:", "<", "#")):
                return m.group(0)
            path_part = tgt.split("#")[0]
            if not path_part.endswith(".md") and not path_part.endswith("/"):
                return m.group(0)
            old_dir = os.path.dirname(os.path.join(BASE, src_doc))
            resolved = os.path.normpath(os.path.join(old_dir, path_part))
            if not resolved.startswith(BASE):
                return m.group(0)
            old_rel = os.path.relpath(resolved, BASE)
            mapped = map_doc(old_rel)
            if mapped == old_rel and old_rel not in PURE_RENAMES and old_rel not in REP:
                # unchanged doc (15-ui etc.): keep as-is, path still valid
                return m.group(0)
            newtgt = rel_link(cur_dest, mapped)
            anchor = tgt.split("#", 1)[1] if "#" in tgt else ""
            return f"[{label}]({newtgt}{('#' + anchor) if anchor else ''})"
        text = MDLINK.sub(link_sub, text)
        # bare .md path mentions of renamed/dissolved docs (prose)
        for oldp in list(PURE_RENAMES) + list(REP):
            mapped = map_doc(oldp)
            ob, nb = os.path.basename(oldp), os.path.basename(mapped.replace("OUT:", ""))
            if ob != nb:
                text = text.replace(ob, nb)
        # Q-number refs
        out, last = [], 0
        for m in re.finditer(r"Q(\d+)", text):
            n = int(m.group(1))
            head = text[max(0, m.start() - 140):m.start()]
            ref_old, best = None, -1
            for stem, oldp in doc_of_stem.items():
                pos = head.rfind(stem)
                if pos > best:
                    best, ref_old = pos, oldp
            if best < 0:
                for al, oldp in ALIAS.items():
                    pos = head.rfind(al)
                    if pos > best:
                        best, ref_old = pos, oldp
            if ref_old is None:
                ref_old = src_doc
            key = (ref_old, n)
            if key not in newnum:
                out.append(text[last:m.end()]); last = m.end(); continue
            dst, nq = newnum[key]
            if dst == cur_dest:
                out.append(text[last:m.start()]); out.append(f"Q{nq}")
            else:
                out.append(text[last:m.start()]); out.append(f"{os.path.basename(dst)[:-3]} Q{nq}")
            last = m.end()
        out.append(text[last:])
        return "".join(out)

    if apply:
        os.makedirs(os.path.join(BASE, "16-app-framework"), exist_ok=True)
        for d in ["06-platform-services", "17-aaos"]:
            os.makedirs(os.path.join(BASE, d), exist_ok=True)
        os.makedirs(os.path.join(KB, "网络"), exist_ok=True)
        os.makedirs(os.path.join(KB, "career/work-project-analysis"), exist_ok=True)

    written = []
    for dst, lst in sorted(order.items()):
        target = os.path.join(ROOT, dst[4:]) if dst.startswith("OUT:") else os.path.join(BASE, dst)
        parts = []
        if dst in P:
            h1, ptext = P[dst]
            parts.append(f"# {h1}\n\n{ptext}")
        else:
            first_src = lst[0][0]
            srcdoc = docs[first_src]
            ptext = rewrite(srcdoc["preamble"], first_src, dst) if srcdoc["preamble"] else ""
            h1 = srcdoc["h1"] or os.path.basename(dst)[:-3]
            parts.append(f"# {h1}\n\n{ptext}" if ptext else f"# {h1}")
        for i, (src, q) in enumerate(lst):
            blk = docs[src]["blocks"][q]
            marker = re.sub(r"Q\d+", f"Q{i+1}", blk["marker"], count=1)
            ans = rewrite(blk["answer"], src, dst)
            if dst == "13-audio/03-audio-latency.md" and src == "13-audio/03-音频延迟与应用实践.md" and q == 2:
                ans += "\n\n补充（设备面与 AAOS 语境，自原 11 册同题合并）：Audio HAL 将 bus 数据交给驱动、DSP 或功放，最终从指定扬声器发声；若策略选 Direct/Offload/MMAP，数据通路会变化，但仍需策略与 HAL 配合。"
            parts.append(f"{marker}\n\n{ans}")
        body = "\n\n".join(parts) + "\n"
        if apply:
            os.makedirs(os.path.dirname(target), exist_ok=True)
            open(target, "w", encoding="utf-8").write(body)
        written.append(dst)

    # ---------- ledger + path map ----------
    invj = json.load(open(os.path.join(OUTDIR, "inventory.json")))
    inv = {(q["old_path"], q["old_q"]): q for q in invj["questions"]}
    cols = ["old_path", "old_q", "old_title", "old_status", "old_tags", "old_body_sha256",
            "core_question", "action", "new_path", "new_q", "new_title", "evidence_version", "related_old_ids", "reviewer", "review_state"]
    rows = []
    for (src, q), dst in covered.items():
        meta = inv[(src, q)]
        nd, nq = newnum[(src, q)]
        action = "keep" if (src in PURE_RENAMES and PURE_RENAMES[src] == dst and src not in REP) else "move"
        rows.append([src, f"Q{q}", meta["old_title"], meta["old_status"], meta["old_tags"], meta["body_sha16"],
                     meta["old_title"], action, nd, f"Q{nq}", meta["old_title"], "保留原题证据标注", f"{src}#Q{q}", "executor", "migrated"])
    q43_new = newnum[("01-architecture/02-Android系统启动流程.md", 43)]
    q11_new = newnum[("18-build-system/02-soong-modules.md", 11)]
    rows.append(["17-car-app/01-Launcher.md", "Q2", inv[("17-car-app/01-Launcher.md", 2)]["old_title"], "todo", "",
                 inv[("17-car-app/01-Launcher.md", 2)]["body_sha16"], inv[("17-car-app/01-Launcher.md", 2)]["old_title"],
                 "remove-duplicate", q43_new[0], f"Q{q43_new[1]}", "", "启动册 HOME 解析题已覆盖", "17-car-app/01-Launcher.md#Q2", "executor", "migrated"])
    rows.append(["18-build-system/02-soong-modules.md", "Q10", inv[("18-build-system/02-soong-modules.md", 10)]["old_title"], "todo", "",
                 inv[("18-build-system/02-soong-modules.md", 10)]["body_sha16"], inv[("18-build-system/02-soong-modules.md", 10)]["old_title"],
                 "remove-duplicate", q11_new[0], f"Q{q11_new[1]}", "", "原 Q11 已覆盖声明→构建动作链路", "18-build-system/02-soong-modules.md#Q10", "executor", "migrated"])
    rows.append(["13-audio/11-playback-hal.md", "Q2", inv[("13-audio/11-playback-hal.md", 2)]["old_title"], "todo", "",
                 inv[("13-audio/11-playback-hal.md", 2)]["body_sha16"], inv[("13-audio/11-playback-hal.md", 2)]["old_title"],
                 "merge", "13-audio/03-audio-latency.md", "Q2", "", "控制面/数据面同题，设备面补充并入 03 册 Q2 答案", "13-audio/03-音频延迟与应用实践.md#Q2", "executor", "migrated"])
    rows.sort(key=lambda r: (r[0], int(r[1][1:])))
    if apply:
        with open(os.path.join(OUTDIR, "question-ledger.tsv"), "w", encoding="utf-8") as f:
            f.write("\t".join(cols) + "\n")
            for r in rows:
                f.write("\t".join(r) + "\n")
    # path map
    pmap = {}
    for src, dst in PURE_RENAMES.items():
        if src != dst:
            pmap[src] = (dst, "rename to kebab-case English stem")
    for src, dst in REP.items():
        pmap[src] = (dst, "dissolved; representative destination (see question-ledger.tsv for per-Q)")
    with open(os.path.join(OUTDIR, "path-map.tsv"), "w", encoding="utf-8") as f:
        f.write("old_path\tnew_path\treason\taffected_links_checked\n")
        for s in sorted(pmap):
            d, why = pmap[s]
            f.write(f"{s}\t{d}\t{why}\n")
    print(f"ledger rows: {len(rows)}; path-map entries: {len(pmap)}; destinations: {len(written)}")

if __name__ == "__main__":
    main(apply="--check" not in sys.argv)

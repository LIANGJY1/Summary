#!/usr/bin/env python3
"""Task 1 link scan: all markdown links + prose references to 01-android paths/Qn across the repo."""
import json
import os
import re

ROOT = "/home/liang/Project/MyProject/Summary"
OUT = os.path.join(ROOT, "docs/superpowers/plans/android-kb-restructure")

MD_LINK = re.compile(r"\[([^\]\n]*)\]\(([^)\s]+)\)")
# prose refs like: 01-architecture/03-Binder.md 的 Q5  /  03-Binder.md#Q5  / 《xx.md》Q5  / xx.md Q5
PROSE_REF = re.compile(r"([0-9A-Za-z\u4e00-\u9fff\-_#/\.]+\.md)(?:[#\s（(]*Q(\d+))?")


def main():
    links = []
    for dirpath, dirnames, filenames in os.walk(ROOT):
        dirnames[:] = [d for d in dirnames if d not in (".git", "node_modules", ".gradle", "build")]
        for fn in filenames:
            if not fn.endswith(".md"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            try:
                text = open(path, encoding="utf-8").read()
            except Exception:
                continue
            fenced = False
            for i, line in enumerate(text.split("\n")):
                t = line.lstrip()
                if t.startswith("```") or t.startswith("~~~"):
                    fenced = not fenced
                    continue
                if fenced:
                    continue
                for m in MD_LINK.finditer(line):
                    target = m.group(2)
                    if target.startswith(("http://", "https://", "mailto:")):
                        continue
                    links.append({"from": rel, "line": i + 1, "target": target})
    with open(os.path.join(OUT, "links.json"), "w", encoding="utf-8") as f:
        json.dump(links, f, ensure_ascii=False, indent=1)
    # links that point into 01-android
    kb_links = [l for l in links if "01-android" in l["target"] or l["target"].startswith("../")]
    print(f"total_rel_links={len(links)}")
    # resolve relative links and flag broken ones for 01-android scope
    broken = []
    for l in links:
        tgt = l["target"].split("#")[0]
        if not tgt:
            continue
        base_dir = os.path.dirname(os.path.join(ROOT, l["from"]))
        resolved = os.path.normpath(os.path.join(base_dir, tgt))
        if os.path.sep + "01-android" + os.path.sep in resolved or "/01-android" in resolved:
            if not os.path.exists(resolved):
                broken.append(l)
    print(f"links_touching_01android_broken={len(broken)}")
    for b in broken[:50]:
        print(f"  BROKEN: {b['from']}:{b['line']} -> {b['target']}")
    # in-repo markdown files count for context
    n_md = sum(1 for dp, dn, fn in os.walk(ROOT) for f in fn if f.endswith(".md"))
    print(f"repo_md_total={n_md}")


if __name__ == "__main__":
    main()

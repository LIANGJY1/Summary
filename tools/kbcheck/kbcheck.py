#!/usr/bin/env python3
"""知识库机械规范体检工具。

覆盖 WRITING-GUIDE 可机械验证的检查项，全部扫描后输出汇总报告：

1.  分号串列       ——正文/列表项用 ≥2 个全角分号连接并列项（依据行/表格/代码块豁免）
2.  粗体叠行内码   ——同一文本上同时套 **粗体** 与 `行内码`（粗体 span 内含反引号）
3.  连续空行       ——题间多余空行（代码块内豁免；--fix 空行可自动收敛）
4.  日期元数据     ——正文/引言中的「日期+变更动词」残留（代码块豁免）
5.  代码块语言标注 ——``` 后缺语言标注
6.  图片引用存在性 ——![]() 相对引用按 URL 解码后检查目标存在性
7.  内部链接死链   ——[text](相对路径.md) 目标存在性
8.  题号连续性     ——每册 Q1..Qn 连续无跳号
9.  README 计数链  ——目录 README 声明的总题数与实际题数核对
10. 状态标记分布   ——[learning]/[done]/无标记统计（只报告，不做合规判断）

用法：
    python3 kbcheck.py                 # 只报告
    python3 kbcheck.py --fix-blank     # 报告并自动收敛连续空行
    python3 kbcheck.py --root <path>   # 指定知识库根（默认脚本上两级 knowledge-base）
"""
import os, re, sys, urllib.parse

BT = chr(96)
DATE_PAT = re.compile(r"(新增\s*\d+\s*题|扩充|重构|更名|迁入|迁移|改写|重写|追加|二次修订|三次修订|修订：)")
CLAIM_PAT = re.compile(r"收录[^。]*?(\d+)\s*篇[^。]*?(\d+)\s*道?题")


def walk_md(root, dirs=None):
    for d in (dirs or sorted(os.listdir(root))):
        dp = os.path.join(root, d)
        if not os.path.isdir(dp):
            continue
        for r, _, fs in os.walk(dp):
            for fn in sorted(fs):
                if fn.endswith(".md"):
                    yield os.path.join(r, fn)


def bold_violation(ln):
    """粗体 span 内含反引号的次数。"""
    i, n, c = 0, len(ln), 0
    while i < n:
        if ln.startswith("**", i):
            j = ln.find("**", i + 2)
            if j == -1:
                break
            if BT in ln[i + 2:j]:
                c += 1
            i = j + 2
        else:
            nxt = ln.find("**", i)
            if nxt == -1:
                break
            i = nxt
    return c


def scan(root, fix_blank):
    res = {"分号串列": [], "粗体叠码": [], "连续空行": [], "日期元数据": [],
           "缺语言标注": [], "图片死链": [], "md死链": [], "题号跳号": [], "README计数": []}
    states = {"learning": 0, "done": 0, "none": 0}
    for p in walk_md(root):
        rel = os.path.relpath(p, root)
        dp = os.path.dirname(p)
        text = open(p, encoding="utf-8").read()
        lines = text.split("\n")
        in_code, blank, blanks_at = False, 0, []
        for i, ln in enumerate(lines, 1):
            if ln.strip().startswith("```"):
                in_code = not in_code
                if in_code and not ln.strip()[3:].strip():
                    res["缺语言标注"].append(f"{rel}:{i}")
                blank = 0
                continue
            if in_code:
                continue
            if ln.strip() == "":
                blank += 1
                if blank > 1:
                    blanks_at.append(i - blank + 1)
                continue
            blank = 0
            body = not ln.startswith(("依据", ">"))
            if body and ln.count("；") >= 2:
                res["分号串列"].append(f"{rel}:{i}")
            v = bold_violation(ln)
            if v:
                res["粗体叠码"].append(f"{rel}:{i}({v})")
            if ln.startswith(">") and DATE_PAT.search(ln):
                res["日期元数据"].append(f"{rel}:{i}")
            if re.match(r"^\*\*Q\d+：\*\*", ln):
                res["题号跳号"].append(f"{rel}:{i} 全角冒号题干")
            for m in re.finditer(r"!\[[^\]]*\]\(([^)#]+)\)", ln):
                tgt = os.path.normpath(os.path.join(dp, urllib.parse.unquote(m.group(1).split()[0])))
                if not tgt.startswith("http") and not os.path.exists(tgt):
                    res["图片死链"].append(f"{rel} -> {m.group(1)[:60]}")
            for m in re.finditer(r"\]\(([^)#\s]+\.md)(#[^)]*)?\)", ln):
                tgt = m.group(1)
                if tgt.startswith("http"):
                    continue
                tp = os.path.normpath(os.path.join(dp, tgt))
                if not os.path.exists(tp):
                    res["md死链"].append(f"{rel} -> {tgt}")
        if blanks_at:
            res["连续空行"].append(f"{rel}:{blanks_at}")
            if fix_blank:
                out, in_code, b = [], False, 0
                for ln in lines:
                    if ln.strip().startswith("```"):
                        in_code = not in_code
                        out.append(ln); b = 0; continue
                    if in_code:
                        out.append(ln); continue
                    if ln.strip() == "":
                        b += 1
                        if b <= 1:
                            out.append(ln)
                        continue
                    b = 0
                    out.append(ln)
                open(p, "w", encoding="utf-8").write("\n".join(out))
        # 题号与状态
        nums = [int(m.group(1)) for m in re.finditer(r"^\*\*Q(\d+):", text, re.M)]
        if nums != list(range(1, len(nums) + 1)):
            res["题号跳号"].append(f"{rel}: {nums[:12]}...")
        states["learning"] += len(re.findall(r"^\*\*Q\d+: \[learning\]", text, re.M))
        states["done"] += len(re.findall(r"^\*\*Q\d+: \[done\]", text, re.M))
    # README 计数链
    for d in sorted(os.listdir(root)):
        dp = os.path.join(root, d)
        readme = os.path.join(dp, "README.md")
        if not os.path.isdir(dp) or d.startswith(("_", "atlas")) or not os.path.exists(readme):
            continue
        actual = sum(len(re.findall(r"^\*\*Q\d+", open(os.path.join(r, x), encoding="utf-8").read(), re.M))
                     for r, _, f2 in os.walk(dp) for x in f2 if x.endswith(".md") and x != "README.md")
        m = CLAIM_PAT.search(open(readme, encoding="utf-8").read())
        if m and int(m.group(2)) != actual:
            res["README计数"].append(f"{d}/: 声明{m.group(2)} 实际{actual}")
    res["状态分布"] = states
    return res


def main():
    fix_blank = "--fix-blank" in sys.argv
    # 脚本位于 <repo>/tools/kbcheck/，知识库根为 <repo>/knowledge-base
    root = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))), "knowledge-base")
    if "--root" in sys.argv:
        root = sys.argv[sys.argv.index("--root") + 1]
    res = scan(root, fix_blank)
    total = sum(len(v) for k, v in res.items() if isinstance(v, list))
    for k, v in res.items():
        if k == "状态分布":
            print(f"状态分布: learning={v['learning']} done={v['done']} 无标记=信息项")
            continue
        mark = "OK " if not v else f"{len(v):3d}"
        print(f"[{mark}] {k}")
        for item in v[:8]:
            print(f"      {item}")
        if len(v) > 8:
            print(f"      ... 共 {len(v)} 处")
    print(f"\nTOTAL 违规: {total}" + ("（已自动收敛连续空行）" if fix_blank else ""))


if __name__ == "__main__":
    main()

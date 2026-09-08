#!/usr/bin/env python3
"""source-annotator 注释校验脚本 v2（skill v1.23 §3.4 校验闭环用）。

用法:
    python3 check_annotations.py <仓库根> <文件1> <文件2> ... [--fix] [--max-width N]
    python3 check_annotations.py --self-test

九项检查:
  1. 代码序列与 HEAD 一致(过滤本语言行注释与空行后逐行比对;行尾注释先剥离再比对,
     "x = 1; // 改注释"不再误报。注释改动不允许碰代码/Javadoc/文档字符串)
  2. 体系外【标签】(不在 16 标签白名单内)
  3. 冒号式伪标签(目的:/收益:/替代:/实现:/启示:/根因:/防线检查:,仅行首引导形态)
  4. 旧 `注：` 前缀残留(//、#、-- 任意前缀形态)
  5. 行宽超上限——上限自适应 = max(130, 该文件 HEAD 既有行最大显示列),--max-width 覆盖;
     只查新增行与中文批注行(上游存量行不查);分割线放行
  6. 圈号列表编号残留(①②③…已废弃,有序列表统一 1. 2. 3.;句中"阶段①"类引用放行)
  7. 行尾缺标点(中文批注行)——默认 FAIL 并提示按语义断点重排;--fix 自动补句号写盘
     (已知副作用:会把无标点续行在句中切成病句,优先人工重排而非 --fix)
  8. 上游英文注释保护:HEAD 中的英文散文注释行(无 CJK、长度>15、含词间空格、非分割线、
     无 →/【)被删除或改写 → FAIL——四检范围仅限此前轮次的中文学习批注
  9. 未带标签的新注释块 / 新行标签出现在块中段 → ? WARN(不影响退出码;确认属类头总论/
     方法头则罢,否则补【标签】;空注释行是块分隔符)

注释前缀按扩展名:`//`(java/kt/scala/js/ts/c/cpp/go/rs/swift/cs/php…)、
`#`(py/sh/yaml/toml/rb/pl…)、`--`(sql/lua/hs)。

输出:每文件 PASS/FAIL + 明细(? 行为 WARN,+ 行为 --fix 已应用项)。
退出码:0=全绿,1=有 FAIL,2=用法错误。--self-test 跑内置回归夹具(脚本自身改动的验证入口)。
"""
import re
import subprocess
import sys

ALLOWED_TAGS = {
    "设计思想", "核心流程", "调用关系", "数据流", "执行时机", "生命周期",
    "线程模型", "状态管理", "为什么", "关键细节", "性能考虑", "安全考虑",
    "兼容性", "注意事项", "关联代码", "TODO",
}
PSEUDO_LABEL = re.compile(r"(目的|实现|收益|替代|启示|根因|防线检查)：")
OLD_NOTE = re.compile(r"^注：")
CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭"
END_PUNCT = set("。？！，,.?!;；、：:）)】］\"'*…—/")
DIVIDER_CHARS = set("─━-=_·•*~ ")
DEFAULT_CAP = 130

PREFIX_BY_EXT = {}
for _e in (".java .kt .kts .scala .groovy .js .ts .tsx .jsx .c .h .cpp .hpp .cc .hh "
           ".go .rs .swift .cs .php").split():
    PREFIX_BY_EXT[_e] = "//"
for _e in ".py .pyw .sh .bash .zsh .yaml .yml .toml .rb .pl .pm .bp".split():
    PREFIX_BY_EXT[_e] = "#"
for _e in ".sql .lua .hs".split():
    PREFIX_BY_EXT[_e] = "--"


def display_width(s: str) -> int:
    return sum(2 if ord(c) > 0x2E80 else 1 for c in s)


def has_cjk(s: str) -> bool:
    return any(ord(c) > 0x2E80 for c in s)


def prefix_for(path: str) -> str:
    dot = path.rfind(".")
    return PREFIX_BY_EXT.get(path[dot:] if dot >= 0 else "", "//")


def split_comment(line: str, prefix: str):
    """返回 (是否注释行, 注释体)。体 = 前缀之后去首尾空白。"""
    s = line.strip()
    if s.startswith(prefix):
        return True, s[len(prefix):].strip()
    return False, ""


def is_divider(body: str) -> bool:
    return bool(body) and set(body) <= DIVIDER_CHARS


def is_upstream_prose(body: str) -> bool:
    """HEAD 注释体是否英文散文(上游作者注释,删改即 FAIL)。

    启发式边界:中文批注里的纯 ASCII 行(箭头链路有 →、标签行有 【、符号列表含 CJK)
    均不命中;无词间空格的长标识符串也不命中。误报时人工复核,不要静默放宽。
    """
    if not body or has_cjk(body) or is_divider(body):
        return False
    if len(body) <= 15 or "→" in body or "【" in body:
        return False
    return bool(re.search(r"[A-Za-z][A-Za-z0-9_.]*[ \t]+[A-Za-z][A-Za-z0-9_.]*", body))


def strip_trailing_comment(line: str, prefix: str) -> str:
    """剥离行尾注释(供代码序列比对):marker 前是空白/行首且引号配对平衡才剥。"""
    s = line.rstrip()
    for m in re.finditer(re.escape(prefix), s):
        before = s[:m.start()]
        if before and before[-1] not in " \t":
            continue
        if before.count('"') % 2 or before.count("'") % 2:
            continue
        return before.rstrip()
    return s


def head_lines(repo: str, path: str):
    out = subprocess.run(["git", "-C", repo, "show", f"HEAD:{path}"],
                         capture_output=True, text=True)
    return out.stdout.splitlines() if out.returncode == 0 else None


def code_sequence(lines, prefix: str):
    """过滤整行注释与空行后的代码序列;行尾注释剥离后比对。"""
    seq = []
    for l in lines:
        ic, _ = split_comment(l, prefix)
        if ic or not l.strip():
            continue
        seq.append(strip_trailing_comment(l, prefix))
    return seq


def check_file(repo: str, path: str, fix: bool = False, max_width=None):
    """返回 (problems, warns, fixes, new_lines)。problems/warns/fixes 均为字符串列表。"""
    prefix = prefix_for(path)
    problems, warns, fixes = [], [], []
    try:
        with open(f"{repo}/{path}", encoding="utf-8") as fh:
            new_lines = fh.read().splitlines()
    except OSError as e:
        return [f"无法读取: {e}"], [], [], None

    old = head_lines(repo, path)
    old_set = set()
    if old is None:
        problems.append("git show HEAD 取旧版失败(文件是新增?手动核对代码序列)")
        cap = max_width if max_width else DEFAULT_CAP
    else:
        file_max = max((display_width(l.rstrip()) for l in old), default=0)
        cap = max_width if max_width else max(file_max, DEFAULT_CAP)
        old_set = {l.strip() for l in old}
        # 8 上游注释保护:HEAD 英文散文行必须原样保留
        new_stripped = {l.strip() for l in new_lines}
        for l in old:
            ic, body = split_comment(l, prefix)
            if ic and is_upstream_prose(body) and l.strip() not in new_stripped:
                problems.append(f"上游英文注释被删/改(上游只读;四检限中文学习批注): {l.strip()[:44]}")

    # 块结构:块首 = 上一行非注释的注释行;空注释行是块内分隔符,不切断块
    block_start = {}
    cur_start, prev_comment = None, False
    for i, l in enumerate(new_lines, 1):
        ic, _ = split_comment(l, prefix)
        if not ic:
            prev_comment = False
            continue
        if not prev_comment:
            cur_start = i
        block_start[i] = cur_start
        prev_comment = True

    for i, l in enumerate(new_lines, 1):
        ic, body = split_comment(l, prefix)
        if not ic:
            continue
        s = l.strip()
        is_new = s not in old_set
        # 2 体系外标签
        if body.startswith("【"):
            m = re.match(r"【([^】]+)】", body)
            if not m or m.group(1) not in ALLOWED_TAGS:
                problems.append(f"L{i} 体系外标签: {body[:40]}")
        # 3 冒号式伪标签
        if PSEUDO_LABEL.match(body):
            problems.append(f"L{i} 冒号式伪标签: {body[:40]}")
        # 4 旧 `注：` 前缀
        if OLD_NOTE.match(body):
            problems.append(f"L{i} 旧 `注：` 前缀残留")
        # 5 行宽(新增行或中文批注行;分割线放行)
        if (is_new or has_cjk(s)) and not is_divider(body):
            w = display_width(l.rstrip())
            if w > cap:
                problems.append(f"L{i} 行宽 {w} > {cap}")
        # 6 圈号(只看行首行尾,句中"阶段①"类引用放行)
        if body and (body[0] in CIRCLED or body[-1] in CIRCLED):
            problems.append(f"L{i} 圈号列表编号残留(用 1. 2. 3.): {body[:40]}")
        # 7 行尾标点(中文批注行;分割线放行)
        if has_cjk(s) and body and not is_divider(body) and s[-1] not in END_PUNCT:
            if s.count("（") > s.count("）"):
                problems.append(f"L{i} 括号内折行,需人工重排断点: …{s[-14:]}")
            elif fix:
                fixes.append((i, f"L{i} 补行尾句号(可能切病句,建议按语义断点人工重排)"))
            else:
                problems.append(f"L{i} 行尾缺标点(按语义断点重排;或 --fix 自动补,有切病句副作用): …{s[-14:]}")
        # 9 未带标签的新块 / 块中段标签
        if is_new and body and not is_divider(body):
            if i == block_start[i] and not body.startswith("【"):
                warns.append(f"L{i} 未带标签的新注释块(类头总论/方法头则罢,否则补【标签】)")
            if i != block_start[i] and body.startswith("【"):
                warns.append(f"L{i} 标签出现在块中段(块应以【标签】开头,块间用空注释行)")

    if old is not None and code_sequence(old, prefix) != code_sequence(new_lines, prefix):
        problems.append("代码序列与 HEAD 不一致!(注释改动碰到了代码,立即检查)")
    return problems, warns, fixes, new_lines


def apply_fixes(repo: str, path: str, fixes, new_lines):
    """--fix 写盘:仅追加行尾句号;代码序列有损时绝不写。"""
    if not fixes or new_lines is None:
        return
    lines = new_lines[:]
    for i, _msg in fixes:
        lines[i - 1] = lines[i - 1].rstrip() + "。"
    with open(f"{repo}/{path}", "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")


def self_test() -> int:
    import os
    import tempfile

    W150 = "// " + "w" * 147  # 150 显示列
    fixtures = [
        dict(name="删代码行被抓", ext=".java",
             old="class A {\n  int x = 1;\n}\n", new="class A {\n}\n",
             must=["代码序列"]),
        dict(name="上游英文注释删除被抓", ext=".java",
             old="class A {\n  // This tracker keeps the pending targets for later cleanup.\n  int x = 1;\n}\n",
             new="class A {\n  int x = 1;\n}\n",
             must=["上游"]),
        dict(name="中文学习批注重写放行", ext=".java",
             old="class A {\n  // 【关键细节】弱引用登记只观察不挽留，强可达性由请求链保证。\n  int x = 1;\n}\n",
             new="class A {\n  // 【关键细节】登记簿弱引用：强可达性靠 Request 持有 target 字段。\n  int x = 1;\n}\n",
             must_not=["上游", "代码序列"]),
        dict(name="存量超宽行不报(只查新增)", ext=".java",
             old="class A {\n  // " + "w" * 265 + "\n  int x = 1;\n}\n",
             new="class A {\n  // " + "w" * 265 + "\n  // 【关键细节】新增短批注。\n  int x = 1;\n}\n",
             must_not=["行宽"]),
        dict(name="新增行超自适应上限被抓", ext=".java",
             old=f"class A {{\n  {W150}\n  int x = 1;\n}}\n",
             new="class A {\n  " + W150 + "\n  // 【关键细节】" + "超" * 70 + "。\n  int x = 1;\n}\n",
             must=["行宽"]),
        dict(name="新增行在上限内放行", ext=".java",
             old=f"class A {{\n  {W150}\n  int x = 1;\n}}\n",
             new="class A {\n  " + W150 + "\n  // 【关键细节】" + "x" * 118 + "。\n  int x = 1;\n}\n",
             must=[]),
        dict(name="行尾注释改动不误报代码序列", ext=".java",
             old="class A {\n  int x = 1; // 注：惰性包装\n}\n",
             new="class A {\n  int x = 1; // 【关键细节】惰性包装，避免重复分配。\n}\n",
             must_not=["代码序列"]),
        dict(name="python 伪标签被抓", ext=".py",
             old="def f():\n    pass\n",
             new="def f():\n    # 目的：测试伪标签检测。\n    pass\n",
             must=["伪标签"]),
        dict(name="python 标签批注合规", ext=".py",
             old="def f():\n    pass\n",
             new="def f():\n    # 【关键细节】仅首次调用时初始化，后续直接复用。\n    pass\n",
             must=[]),
        dict(name="分割线放行", ext=".java",
             old="class A {\n}\n", new="class A {\n// ───\n}\n",
             must=[]),
        dict(name="未带标签新块告警", ext=".java",
             old="class A {\n}\n",
             new="class A {\n// 取数契约：A 与数据源之间的统一接口，结果二选一回调。\n}\n",
             must=[], warn_contains="未带标签"),
        dict(name="体系外标签被抓", ext=".java",
             old="class A {\n}\n", new="class A {\n// 【阶段】旧标签不该出现。\n}\n",
             must=["体系外"]),
        dict(name="旧前缀被抓", ext=".java",
             old="class A {\n}\n", new="class A {\n// 注：旧前缀形态。\n}\n",
             must=["注："]),
        dict(name="SQL 形态支持", ext=".sql",
             old="SELECT 1;\n",
             new="-- 【注意事项】该查询会全表扫描，先确认索引。\nSELECT 1;\n",
             must=[]),
        dict(name="圈号残留被抓", ext=".java",
             old="class A {\n}\n", new="class A {\n// ①第一点：初始化顺序不可交换。\n}\n",
             must=["圈号"]),
    ]

    failures = []
    for fx in fixtures:
        with tempfile.TemporaryDirectory() as tmp:
            subprocess.run(["git", "init", "-q", tmp], check=True)
            subprocess.run(["git", "-C", tmp, "config", "user.email", "t@t"], check=True)
            subprocess.run(["git", "-C", tmp, "config", "user.name", "t"], check=True)
            os.makedirs(f"{tmp}/src", exist_ok=True)
            path = f"src/a{fx['ext']}"
            with open(f"{tmp}/{path}", "w", encoding="utf-8") as fh:
                fh.write(fx["old"])
            subprocess.run(["git", "-C", tmp, "add", "-A"], check=True)
            subprocess.run(["git", "-C", tmp, "commit", "-qm", "old"], check=True)
            with open(f"{tmp}/{path}", "w", encoding="utf-8") as fh:
                fh.write(fx["new"])
            problems, warns, _fixes, _nl = check_file(tmp, path)
            ok = all(any(m in p for p in problems) for m in fx.get("must", []))
            ok &= not any(any(m in p for p in problems) for m in fx.get("must_not", []))
            if "warn_contains" in fx:
                ok &= any(fx["warn_contains"] in w for w in warns)
            print(f"{'PASS' if ok else 'FAIL'} 夹具: {fx['name']}")
            if not ok:
                failures.append(fx["name"])
                print(f"     problems={problems}\n     warns={warns}")

    # --fix 写盘
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["git", "init", "-q", tmp], check=True)
        subprocess.run(["git", "-C", tmp, "config", "user.email", "t@t"], check=True)
        subprocess.run(["git", "-C", tmp, "config", "user.name", "t"], check=True)
        os.makedirs(f"{tmp}/src", exist_ok=True)
        with open(f"{tmp}/src/a.java", "w", encoding="utf-8") as fh:
            fh.write("class A {\n}\n")
        subprocess.run(["git", "-C", tmp, "add", "-A"], check=True)
        subprocess.run(["git", "-C", tmp, "commit", "-qm", "old"], check=True)
        with open(f"{tmp}/src/a.java", "w", encoding="utf-8") as fh:
            fh.write("class A {\n// 【关键细节】缺行尾标点\n}\n")
        problems, _w, fixes, nl = check_file(tmp, "src/a.java", fix=True)
        apply_fixes(tmp, "src/a.java", fixes, nl)
        content = open(f"{tmp}/src/a.java", encoding="utf-8").read()
        ok = bool(fixes) and "缺行尾标点。" in content
        print(f"{'PASS' if ok else 'FAIL'} 夹具: --fix 补句号写盘")
        if not ok:
            failures.append("--fix")

    # 无法读取不崩溃
    with tempfile.TemporaryDirectory() as tmp:
        problems, _w, _f, _nl = check_file(tmp, "src/nope.java")
        ok = any("无法读取" in p for p in problems)
        print(f"{'PASS' if ok else 'FAIL'} 夹具: 不存在的文件不崩溃")
        if not ok:
            failures.append("无法读取")

    print("自测总体:", "ALL PASS" if not failures else f"FAIL {failures}")
    return 0 if not failures else 1


def main(argv) -> int:
    args = list(argv[1:])
    if "--self-test" in args:
        return self_test()
    fix = "--fix" in args
    max_width = None
    if "--max-width" in args:
        k = args.index("--max-width")
        try:
            max_width = int(args[k + 1])
        except (IndexError, ValueError):
            print("--max-width 需要整数参数", file=sys.stderr)
            return 2
        del args[k:k + 2]
    files = [a for a in args[1:] if not a.startswith("--")]
    if len(args) < 2 or not files:
        print(__doc__)
        return 2
    repo = args[0]
    all_ok = True
    for f in files:
        problems, warns, fixes, new_lines = check_file(repo, f, fix, max_width)
        if fix and fixes and new_lines is not None and not any("代码序列" in p for p in problems):
            apply_fixes(repo, f, fixes, new_lines)
        ok = not problems
        all_ok &= ok
        print(f"{'PASS' if ok else 'FAIL'} {f}")
        for p in problems:
            print(f"     - {p}")
        for w in warns:
            print(f"     ? {w}")
        for _i, msg in fixes:
            print(f"     + {msg}")
    print("总体:", "ALL PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

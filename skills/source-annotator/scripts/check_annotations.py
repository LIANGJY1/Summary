#!/usr/bin/env python3
"""source-annotator 注释校验脚本 v2.3（skill v1.26 §3.4 校验闭环用）。

用法:
    python3 check_annotations.py <仓库根> <文件1> <文件2> ... [--fix] [--max-width N]
                                [--fast] [--mirror <副本目录>]
    python3 check_annotations.py --self-test

--fast: 跳过第 10 项专名回查——纯格式修正迭代轮用（修正标点不产生新符号），
        避免每轮迭代都付一次全仓 grep；交付前必须跑一次不带 --fast 的全量。
专名缓存: 已核验存在的符号按 仓库+HEAD 提交 缓存在系统临时目录，重复校验零 grep；
        HEAD 变化自动失效，只缓存"存在"结果（缺失符号每次重查，防漏报）。

九项检查:
  1. 代码序列与 HEAD 一致(过滤本语言行注释与空行后逐行比对;行尾注释先剥离再比对,
     "x = 1; // 改注释"不再误报。注释改动不允许碰代码/Javadoc/文档字符串)
  2. 体系外【标签】(不在 16 标签白名单内)
  3. 冒号式伪标签(目的:/收益:/替代:/实现:/启示:/根因:/防线检查:,仅行首引导形态)
  4. 旧 `注：` 前缀残留(//、#、-- 任意前缀形态)
  5. 行宽超上限——上限自适应 = max(130, 该文件 HEAD 既有行最大显示列),--max-width 覆盖;
     只查新增行与中文批注行(上游存量行不查);分割线放行
  6. 圈号列表编号残留(①②③…已废弃,有序列表统一 1. 2. 3.;句中"阶段①"类引用放行)
  7. 行尾缺标点(只查含真汉字的中文批注行;纯 ASCII 示例代码行豁免——格式卡「使用示例块」
     承诺"无 CJK 示例行不参与行尾标点检查",v2.2 修正脚本与文档不一致)——默认 FAIL 并提示
     按语义断点重排;--fix 自动补句号写盘(已知副作用:会把无标点续行切成病句,慎用)
  8. 上游英文注释保护:HEAD 中的英文散文注释行(无 CJK、长度>15、含词间空格、非分割线、
     无 →/【)被删除或改写 → FAIL——四检范围仅限此前轮次的中文学习批注
  9. 未带标签的新注释块 / 新行标签出现在块中段 → ? WARN(不影响退出码;确认属类头总论/
     方法头则罢,否则补【标签】;空注释行是块分隔符)
  10. 专名回查(WARN):新增中文批注里的 类::方法 / 驼峰标识 / 常量名 / .java|.cpp|.h 文件名,
     一次 git grep 全仓核验存在性;带 [inferred] 的行跳过;WARN 不阻塞但须逐条复核——
     把语义门的"专名回查"机械化(跨层签名论断如 返回值/出参 只能靠人,见 SKILL.md §6)

另: --mirror <目录> 校验权威源与本副本逐文件一致(服务 MAINTENANCE.md 的同步约定,
    不一致即 FAIL)。源=本脚本所在 skill 目录。

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

# 10 专名回查:模式与噪声控制
RE_CLASS_METHOD = re.compile(r"([A-Z][A-Za-z0-9_]*::[a-zA-Z_][A-Za-z0-9_]{1,})")
RE_CAMEL = re.compile(r"\b([a-z][a-z0-9]*[A-Z][A-Za-z0-9_]{2,})\b")
RE_CONST = re.compile(r"\b([A-Z][A-Z0-9_]{4,})\b")
RE_SRC_FILE = re.compile(r"\b([A-Za-z0-9_\-]+\.(?:java|kt|cpp|h|cc))\b")
STOPWORDS = {"Android", "Java", "Kotlin"}
INFERRED = "[inferred]"


def extract_symbols(body: str):
    """从一行中文批注提取待核验专名: [(完整串, 回退串或 None)]。

    回退串:类::方法 找不到时退回裸方法名(方法可能在头文件里不带类前缀出现)。
    """
    syms = []
    for m in RE_CLASS_METHOD.finditer(body):
        full = m.group(1)
        syms.append((full, full.split("::", 1)[1]))
    for m in RE_CAMEL.finditer(body):
        syms.append((m.group(1), None))
    for m in RE_CONST.finditer(body):
        syms.append((m.group(1), None))
    for m in RE_SRC_FILE.finditer(body):
        syms.append((m.group(1), None))
    # 去重保序 + 停用词
    seen, out = set(), []
    for full, fb in syms:
        if full in seen or full in STOPWORDS or full in ALLOWED_TAGS:
            continue
        seen.add(full)
        out.append((full, fb))
    return out


def _xref_cache_path(repo: str):
    import hashlib
    import os
    import tempfile
    key = hashlib.sha1(os.path.abspath(repo).encode()).hexdigest()[:16]
    return os.path.join(tempfile.gettempdir(), f"source_annotator_xref_{key}.json")


def _xref_load_cache(repo: str):
    """读缓存 {head, found:[已核验存在的符号]};HEAD 不一致即失效。只缓存正向结果。"""
    import json
    import os
    import subprocess
    r = subprocess.run(["git", "-C", repo, "rev-parse", "HEAD"],
                       capture_output=True, text=True)
    head = r.stdout.strip() if r.returncode == 0 else ""
    if not head:
        return head, set()
    try:
        with open(_xref_cache_path(repo), encoding="utf-8") as fh:
            data = json.load(fh)
        if data.get("head") == head:
            return head, set(data.get("found", []))
    except (OSError, ValueError):
        pass
    return head, set()


def _xref_save_cache(repo: str, head: str, found):
    import json
    try:
        with open(_xref_cache_path(repo), "w", encoding="utf-8") as fh:
            json.dump({"head": head, "found": sorted(found)}, fh)
    except OSError:
        pass  # 缓存写失败不影响校验结果


def xref_missing(repo: str, syms, use_cache: bool = True):
    """核验专名在**已提交树(HEAD)**中是否存在;返回找不到的 [(完整串, 回退串)]。

    搜 HEAD 而非工作区是关键:未提交的批注文本会"自证"幽灵符号
    (真实事故:标注里写的 ghostHelper 被自己的注释行命中,漏报)。
    两遍式:先一次 `git grep -oI -F -e … HEAD` 大扫描拿候选集(-o 输出
    path:match,按首个冒号切分);候选集未命中的再逐个 `git grep -Fq` 精确确认。
    缓存:命中过的符号记入 仓库+HEAD 级缓存,重复校验零 grep;HEAD 变化自动失效。
    git 不可用/空仓时返回 [](核验不了就不拦,语义门仍有人工回查兜底)。
    """
    head, cached_found = _xref_load_cache(repo) if use_cache else ("", set())
    uniq = {}
    for full, fb in syms:
        uniq.setdefault(full, fb)
    if not uniq:
        return []
    # 缓存命中的符号直接视为存在,只对未缓存符号做扫描/探测
    todo = {full: fb for full, fb in uniq.items()
            if full not in cached_found and not (fb and fb in cached_found)}
    missing = []
    newly = []
    if todo:
        cmd = ["git", "-C", repo, "grep", "-oI", "-F"]
        for full in todo:
            cmd += ["-e", full]
        r = subprocess.run(cmd + ["HEAD", "--"], capture_output=True, text=True)
        if r.returncode not in (0, 1):
            return []
        candidates = {line.split(":", 1)[-1]
                      for line in r.stdout.splitlines() if ":" in line}
        for full, fb in todo.items():
            if full in candidates or (fb and fb in candidates):
                newly.append(full)
                continue
            probe = subprocess.run(["git", "-C", repo, "grep", "-qF", "-e", full, "HEAD", "--"],
                                   capture_output=True, text=True)
            if probe.returncode == 0:
                newly.append(full)
                continue
            if fb:
                probe = subprocess.run(["git", "-C", repo, "grep", "-qF", "-e", fb, "HEAD", "--"],
                                       capture_output=True, text=True)
                if probe.returncode == 0:
                    newly.append(full)
                    continue
            missing.append((full, fb))
    if use_cache and head and newly:
        _xref_save_cache(repo, head, cached_found | set(newly))
    return missing

PREFIX_BY_EXT = {}
for _e in (".java .kt .kts .scala .groovy .js .ts .tsx .jsx .c .h .cpp .hpp .cc .hh "
           ".go .rs .swift .cs .php").split():
    PREFIX_BY_EXT[_e] = "//"
for _e in ".py .pyw .sh .bash .zsh .yaml .yml .toml .rb .pl .pm .bp .rc".split():
    PREFIX_BY_EXT[_e] = "#"
for _e in ".sql .lua .hs".split():
    PREFIX_BY_EXT[_e] = "--"


def display_width(s: str) -> int:
    return sum(2 if ord(c) > 0x2E80 else 1 for c in s)


def has_cjk(s: str) -> bool:
    return any(ord(c) > 0x2E80 for c in s)


def has_ideograph(s: str) -> bool:
    """是否含 CJK 汉字(0x4E00+)——区分中文批注与含假名/符号的英文注释。

    ord>0x2E80 会把假名(如颜文字里的 ツ 0x30C4)也算进来,导致上游英文注释
    被当作中文批注检查(行宽/标点),且上游只读无法修复。存量行只在含真汉字时
    才视为中文批注;新增行一律检查(新增的必为本轮批注)。
    """
    return any(0x4E00 <= ord(c) <= 0x9FFF for c in s)


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
    file_syms = []  # (符号, 回退串, 行号)
    try:
        with open(f"{repo}/{path}", encoding="utf-8") as fh:
            new_lines = fh.read().splitlines()
    except OSError as e:
        return [f"无法读取: {e}"], [], [], None, []

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
        # 5 行宽(新增行,或含真汉字的存量中文批注行;分割线放行)
        if (is_new or has_ideograph(body)) and not is_divider(body):
            w = display_width(l.rstrip())
            if w > cap:
                problems.append(f"L{i} 行宽 {w} > {cap}")
        # 6 圈号(只看行首行尾,句中"阶段①"类引用放行)
        if body and (body[0] in CIRCLED or body[-1] in CIRCLED):
            problems.append(f"L{i} 圈号列表编号残留(用 1. 2. 3.): {body[:40]}")
        # 7 行尾标点(含真汉字的中文批注行;纯 ASCII 示例代码行豁免;分割线放行)
        if has_ideograph(body) and body and not is_divider(body) and s[-1] not in END_PUNCT:
            if s.count("（") > s.count("）"):
                problems.append(f"L{i} 括号内折行,需人工重排断点: …{s[-14:]}")
            elif fix:
                fixes.append((i, f"L{i} 补行尾句号(可能切病句,建议按语义断点人工重排)"))
            else:
                problems.append(f"L{i} 行尾缺标点(按语义断点重排;或 --fix 自动补,有切病句副作用): …{s[-14:]}")
        # 10 专名提取(只查新增中文批注行;[inferred] 行整行豁免)
        if is_new and has_ideograph(body) and INFERRED not in body:
            file_syms.extend((sym, fb, i) for sym, fb in extract_symbols(body))
        # 9 未带标签的新块 / 块中段标签
        if is_new and body and not is_divider(body):
            if i == block_start[i] and not body.startswith("【"):
                warns.append(f"L{i} 未带标签的新注释块(类头总论/方法头则罢,否则补【标签】)")
            if i != block_start[i] and body.startswith("【"):
                warns.append(f"L{i} 标签出现在块中段(块应以【标签】开头,块间用空注释行)")

    if old is not None and code_sequence(old, prefix) != code_sequence(new_lines, prefix):
        problems.append("代码序列与 HEAD 不一致!(注释改动碰到了代码,立即检查)")
    return problems, warns, fixes, new_lines, file_syms


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
        dict(name="纯 ASCII 示例代码行豁免行尾标点", ext=".java",
             old="class A {\n}\n",
             new="class A {\n// 【关键细节】接入方式如下，直接照抄即可。\n"
                 "//   obj.register(listener); // 尾注释也不查\n}\n",
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
            problems, warns, _fixes, _nl, _syms = check_file(tmp, path)
            ok = all(any(m in p for p in problems) for m in fx.get("must", []))
            ok &= not any(any(m in p for p in problems) for m in fx.get("must_not", []))
            if "warn_contains" in fx:
                ok &= any(fx["warn_contains"] in w for w in warns)
            print(f"{'PASS' if ok else 'FAIL'} 夹具: {fx['name']}")
            if not ok:
                failures.append(fx["name"])
                print(f"     problems={problems}\n     warns={warns}")

    # 专名回查专测(extract_symbols + xref_missing,函数级)
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["git", "init", "-q", tmp], check=True)
        subprocess.run(["git", "-C", tmp, "config", "user.email", "t@t"], check=True)
        subprocess.run(["git", "-C", tmp, "config", "user.name", "t"], check=True)
        with open(f"{tmp}/A.java", "w", encoding="utf-8") as fh:
            fh.write("class A {\n  static final int MAX_POINTERS = 16;\n"
                     "  void bar() { } // 定义点,调用处 a.bar()\n}\n")
        subprocess.run(["git", "-C", tmp, "add", "-A"], check=True)
        subprocess.run(["git", "-C", tmp, "commit", "-qm", "old"], check=True)

        syms = extract_symbols("由 A 协作、事件经 A::bar 转发，处理走 eventHubHelper 与 MAX_POINTERS。")
        miss = xref_missing(tmp, syms)
        miss_names = {m[0] for m in miss}
        ok = "eventHubHelper" in miss_names and "A::bar" not in miss_names \
            and "MAX_POINTERS" not in miss_names and "A::bar" in {s0 for s0, _ in syms}
        # 回退串:Foo::bar 回退到裸方法名 bar,仓内存在 → 放行;nope 无处存在 → 上报
        miss2 = xref_missing(tmp, [("Foo::bar", "bar")])
        ok &= not miss2
        miss3 = xref_missing(tmp, [("Foo::nope", "nope")])
        ok &= bool(miss3)
        # [inferred] 豁免在 check_file 调用点(见主流程第 10 项),函数层不重复过滤
        print(f"{'PASS' if ok else 'FAIL'} 夹具: 专名回查(提取/存在放行/缺失告警/回退串/[inferred]豁免)")
        if not ok:
            failures.append("专名回查")
            print(f"     syms={syms}\n     miss={miss} miss2={miss2} miss3={miss3}")

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
        problems, _w, fixes, nl, _syms = check_file(tmp, "src/a.java", fix=True)
        apply_fixes(tmp, "src/a.java", fixes, nl)
        content = open(f"{tmp}/src/a.java", encoding="utf-8").read()
        ok = bool(fixes) and "缺行尾标点。" in content
        print(f"{'PASS' if ok else 'FAIL'} 夹具: --fix 补句号写盘")
        if not ok:
            failures.append("--fix")

    # 无法读取不崩溃
    with tempfile.TemporaryDirectory() as tmp:
        problems, _w, _f, _nl, _syms = check_file(tmp, "src/nope.java")
        ok = any("无法读取" in p for p in problems)
        print(f"{'PASS' if ok else 'FAIL'} 夹具: 不存在的文件不崩溃")
        if not ok:
            failures.append("无法读取")

    print("自测总体:", "ALL PASS" if not failures else f"FAIL {failures}")
    return 0 if not failures else 1


def mirror_diff(src_dir: str, mirror_dir: str):
    """对比源 skill 目录与副本:返回差异清单(仅副本有/仅源有/内容不同)。"""
    import os

    def snapshot(root):
        files = {}
        for base, _dirs, names in os.walk(root):
            _dirs[:] = [d for d in _dirs if d != ".git"]
            for n in names:
                full = os.path.join(base, n)
                rel = os.path.relpath(full, root)
                try:
                    with open(full, "rb") as fh:
                        files[rel] = fh.read()
                except OSError:
                    files[rel] = b"<unreadable>"
        return files

    a, b = snapshot(src_dir), snapshot(mirror_dir)
    diffs = []
    for rel in sorted(set(a) - set(b)):
        diffs.append(f"仅源有: {rel}")
    for rel in sorted(set(b) - set(a)):
        diffs.append(f"仅副本有: {rel}")
    for rel in sorted(set(a) & set(b)):
        if a[rel] != b[rel]:
            diffs.append(f"内容不同: {rel}")
    return diffs


def main(argv) -> int:
    args = list(argv[1:])
    if "--self-test" in args:
        return self_test()
    fix = "--fix" in args
    fast = "--fast" in args
    max_width = None
    mirror = None
    if "--max-width" in args:
        k = args.index("--max-width")
        try:
            max_width = int(args[k + 1])
        except (IndexError, ValueError):
            print("--max-width 需要整数参数", file=sys.stderr)
            return 2
        del args[k:k + 2]
    if "--mirror" in args:
        k = args.index("--mirror")
        if k + 1 >= len(args):
            print("--mirror 需要副本目录参数", file=sys.stderr)
            return 2
        mirror = args[k + 1]
        del args[k:k + 2]
    files = [a for a in args[1:] if not a.startswith("--")]
    if len(args) < 2 or not files:
        print(__doc__)
        return 2
    repo = args[0]

    all_ok = True
    per_file = {}   # f -> (problems, warns, fixes, file_syms)
    for f in files:
        problems, warns, fixes, new_lines, file_syms = check_file(repo, f, fix, max_width)
        per_file[f] = (problems, warns, fixes, file_syms)
        if fix and fixes and new_lines is not None and not any("代码序列" in p for p in problems):
            apply_fixes(repo, f, fixes, new_lines)

    # 10 专名回查:全量汇总 → 一次全仓 grep → 按文件分发 WARN(WARN 不改退出码)
    all_syms = [(sym, fb) for (_p, _w, _fx, syms) in per_file.values() for sym, fb, _i in syms]
    missing = [] if fast else xref_missing(repo, all_syms)
    miss_set = {full for full, _fb in missing}
    line_by_sym = {}
    for f in files:
        for sym, fb, line_no in per_file[f][3]:
            if sym in miss_set:
                line_by_sym.setdefault((f, sym, fb), []).append(line_no)

    for f in files:
        problems, warns, fixes, _syms = per_file[f]
        xref_warns = [f"L{ln} 专名全仓未找到(回退串也无): {sym}"
                      for (sf, sym, _fb), lns in sorted(line_by_sym.items())
                      if sf == f
                      for ln in lns]
        ok = not problems
        all_ok &= ok
        print(f"{'PASS' if ok else 'FAIL'} {f}")
        for p in problems:
            print(f"     - {p}")
        for w in warns + xref_warns:
            print(f"     ? {w}")
        for _i, msg in fixes:
            print(f"     + {msg}")

    if mirror:
        import os
        src_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        diffs = mirror_diff(src_dir, mirror)
        if diffs:
            all_ok = False
            print(f"FAIL mirror 副本不一致(源={src_dir} 副本={mirror}):")
            for d in diffs:
                print(f"     - {d}")
        else:
            print(f"PASS mirror 副本一致: {mirror}")

    if missing:
        print(f"专名回查 WARN 共 {len(missing)} 个符号待复核(不影响退出码,但须逐条确认或标 [inferred])")
    print("总体:", "ALL PASS" if all_ok else "FAIL")
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

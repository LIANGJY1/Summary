#!/usr/bin/env python3
"""project-decoder 架构图校验脚本（references/diagram-guide.md 生成前检查用）。

用法:
    python3 check_mermaid.py <文档.md> [<文档2.md> ...]
    python3 check_mermaid.py --self-test

检查每个 ```mermaid 代码块:
  1. 图类型可识别(graph/flowchart/sequenceDiagram/stateDiagram-v2/…)
  2. nodeId 全 ASCII(中文名必须放 ["…"] 引号标签)
  3. 引号配平(每行双引号成对)
  4. 标签含 CJK/全角字符但未加引号 → 报错
  5. 节点数 ≤12 / 边数 ≤16 / sequenceDiagram 参与者 ≤8(超限报错,逼上卷拆图)
  6. subgraph id 非 ASCII → 报错
仅做静态 lint,不做渲染;渲染验证用 mmdc(diagram-guide 生成前检查第 2 步)。
退出码: 0=全绿, 1=有问题, 2=用法错误。
"""
import re
import sys

KNOWN_TYPES = ("graph", "flowchart", "sequenceDiagram", "stateDiagram-v2",
               "stateDiagram", "classDiagram", "erDiagram", "gantt", "pie",
               "mindmap", "timeline", "journey", "gitGraph", "C4Context")
NODE_CAP, EDGE_CAP, ACTOR_CAP = 12, 16, 8
NODE_DEF = re.compile(r"[A-Za-z][A-Za-z0-9_]*\s*(?:\[|\(\(|\{|\[\[|\(\[)")
EDGES = re.compile(r"-->|-{2,}(?!>)|-{1,2}>|={2,}>|-\.->|\-\-\-")
QUOTED = re.compile(r'"[^"]*"')
NODE_ID = re.compile(r"([A-Za-z][A-Za-z0-9_]*)\s*(?:\[|\(\(|\{|\[\[|\(\[)")


def strip_quoted(line: str) -> str:
    return QUOTED.sub('""', line)


def has_fullwidth(s: str) -> bool:
    return any(ord(c) > 0x2E80 or c in "（）【】：，。％" for c in s)


def lint_block(block: str, idx: int) -> list:
    issues = []
    lines = [l.rstrip() for l in block.strip().splitlines() if l.strip()
             and not l.strip().startswith("%%")]
    if not lines:
        return [f"图{idx}: 空 mermaid 块"]
    first = lines[0].strip()
    if not any(first == t or first.startswith(t + " ") or first.startswith(t + " TL")
               or first.startswith(t + " TB") or first.startswith(t + " LR")
               or first.startswith(t + " RL") or first.startswith(t + " BT")
               for t in KNOWN_TYPES):
        issues.append(f"图{idx}: 首行图类型不可识别: {first[:40]}")

    body = lines[1:]
    if first.startswith(("graph", "flowchart")):
        nodes, edges = set(), 0
        for l in body:
            sq = re.sub(r"\|[^|]*\|", "||", strip_quoted(l))  # 边标签 |中文| 合法,先剥
            edges += len(EDGES.findall(sq))
            for m in NODE_ID.finditer(sq):
                nodes.add(m.group(1))
            if has_fullwidth(sq):
                issues.append(f"图{idx}: 标签含中文/全角但未加引号(用 [\"…\"] 包裹): {l.strip()[:50]}")
            if sq.count('"') % 2:
                issues.append(f"图{idx}: 引号不配平: {l.strip()[:50]}")
            m = re.match(r"\s*subgraph\s+(\S+)", l)
            if m:
                sg_id = m.group(1).split("[")[0]  # SG1["中文标题"] 只查 id 段
                if has_fullwidth(sg_id):
                    issues.append(f"图{idx}: subgraph id 须 ASCII: {sg_id}")
        if len(nodes) > NODE_CAP:
            issues.append(f"图{idx}: 节点 {len(nodes)} > {NODE_CAP},拆图或 subgraph 上卷")
        if edges > EDGE_CAP:
            issues.append(f"图{idx}: 边 {edges} > {EDGE_CAP}")
    elif first.startswith("sequenceDiagram"):
        actors = sum(1 for l in body if re.match(r"\s*participant\s+", l))
        if actors > ACTOR_CAP:
            issues.append(f"图{idx}: 参与者 {actors} > {ACTOR_CAP}")
        for l in body:
            sq = strip_quoted(l)
            if has_fullwidth(sq) and sq.count('"') % 2 and ":" in sq:
                issues.append(f"图{idx}: 消息文本含中文但未加引号: {l.strip()[:50]}")
            if sq.count('"') % 2:
                issues.append(f"图{idx}: 引号不配平: {l.strip()[:50]}")
    else:
        for l in body:
            sq = strip_quoted(l)
            if sq.count('"') % 2:
                issues.append(f"图{idx}: 引号不配平: {l.strip()[:50]}")
    return issues


def check_file(path: str) -> list:
    text = open(path, encoding="utf-8").read()
    blocks = re.findall(r"```mermaid\n(.*?)```", text, re.S)
    issues = []
    for i, b in enumerate(blocks, 1):
        issues += lint_block(b, i)
    if not blocks:
        issues.append("(提示) 未发现 mermaid 代码块——一图流必画(diagram-guide 图类型表)")
    return issues


def self_test() -> int:
    good = '''```mermaid
graph TB
    subgraph SG1["接入层"]
        Entry["入口 Controller"]
    end
    Entry --> Svc["订单服务"]
    Svc --> Repo[("订单表")]
    Svc -.->|发布事件| MQ["消息队列"]
```'''
    bad = '''```mermaid
graph TB
    入口[订单服务(核心)] --> 仓
    仓 --> A["缺引号
    A --> B --> C --> D --> E --> F --> G --> H --> I --> J --> K --> L --> M --> N
```'''
    seq = '''```mermaid
sequenceDiagram
    participant A as 服务一
    participant B as 服务二
    A->>B: 调用("含中文")
```'''
    import tempfile, os
    cases = [(good, 0, "合规图放行"), (bad, 1, "病图被抓"),
             (seq, 0, "时序图放行")]
    ok = True
    for md, want, name in cases:
        with tempfile.NamedTemporaryFile("w", suffix=".md", delete=False,
                                         encoding="utf-8") as f:
            f.write(md)
            p = f.name
        issues = [i for i in check_file(p) if not i.startswith("(提示)")]
        os.unlink(p)
        passed = (len(issues) == 0) if want == 0 else (len(issues) > 0)
        print(f"{'PASS' if passed else 'FAIL'} 夹具: {name}")
        for i in issues:
            print("     -", i)
        ok &= passed
    print("自测总体:", "ALL PASS" if ok else "FAIL")
    return 0 if ok else 1


def main(argv) -> int:
    args = argv[1:]
    if not args:
        print(__doc__)
        return 2
    if args[0] == "--self-test":
        return self_test()
    all_ok = True
    for p in args:
        issues = check_file(p)
        print(("PASS" if not any(not i.startswith("(提示)") for i in issues) else "FAIL"), p)
        for i in issues:
            print("     -", i)
        all_ok &= not any(not i.startswith("(提示)") for i in issues)
    return 0 if all_ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""汇编 PDF 机械 QA：地址扫描/空白页/目录页码全量核对/书眉页脚/元数据/四态计数。"""
import html
import os
import re
import sys
import pymupdf

PDF = os.path.join(os.path.dirname(os.path.abspath(__file__)), "AppList性能全链路解剖-汇编.pdf")
HTML = os.path.join(os.path.dirname(os.path.abspath(__file__)), "combined.html")

doc = pymupdf.open(PDF)
n = doc.page_count
full = "\n".join(p.get_text() for p in doc)
print(f"== 基础 == 页数 {n} | 元数据 {doc.metadata}")

# 1. 内部地址扫描
ips = sorted(set(re.findall(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b", full)))
print(f"== 地址扫描 == 四段IP样匹配: {ips if ips else '无'} | '10.100'出现: {full.count('10.100')} | '9555'出现: {full.count('9555')}")

# 2. 空白页（正文区文字极少）
blank = []
for i, p in enumerate(doc):
    t = p.get_text()
    body = re.sub(r"第 \d+ 页|[ivxlc]+|AppList 性能全链路解剖", "", t)
    body = re.sub(r"^\s*\S.*篇.*$", "", body, flags=re.M)  # 书眉行
    if len(body.strip()) < 40:
        blank.append((i + 1, len(t)))
print(f"== 空白页嫌疑 == {blank if blank else '无'}")

# 3. 目录页码全量核对：按首篇一级书签确定正文起点，页码为全书绝对页码
toc_html = open(HTML, encoding="utf-8").read()
entries = [(cls, hid, html.unescape(re.sub(r"<[^>]+>", "", name)))
           for cls, hid, name in re.findall(r'<div class="(toc-l[12])"><a href="#([^"]+)">(.*?)</a></div>', toc_html, flags=re.S)]
outline = doc.get_toc()
id2page = {}
# 书签标题→页；用 id 映射：从 combined.html 取 id→标题
id2title = dict(re.findall(r'<h([12]) id="([^"]+)"', toc_html))  # level,id
title2page = {t: p for lv, t, p in outline}
# TOC 页文本按行重组（span 按 y 分组，行末 span 为页码）
toc_lines = []
first_body_page = next(p for lv, t, p in outline if lv == 1 and t != "目录")
for pi in range(1, first_body_page - 1):
    d = doc[pi].get_text("dict")
    byy = {}
    for b in d["blocks"]:
        for l in b.get("lines", []):
            y = round(l["bbox"][1], 0)
            for s in l["spans"]:
                byy.setdefault(y, []).append(s)
    for y in sorted(byy):
        spans = sorted(byy[y], key=lambda s: s["bbox"][0])
        txt = "".join(s["text"] for s in spans)
        toc_lines.append((pi + 1, txt, spans))
expected = [(lv, t, p) for lv, t, p in outline if lv <= 2 and t != "目录"]
mismatch, checked = [], 0
if len(expected) != len(entries):
    print(f"  !! 书签序({len(expected)})与目录条目({len(entries)})数量不等")
# 逻辑行重组：长标题折行时累积至行尾页码出现
logical, buf = [], ""
for pg, txt, spans in toc_lines:
    if txt.strip() in ("目录", ""):
        continue
    last = spans[-1]["text"].strip() if spans else ""
    if last.isdigit():
        title_part = "".join(s["text"] for s in spans[:-1])
        acc = (buf + title_part).strip()
        if acc:
            logical.append((acc, int(last)))
        buf = ""
    else:
        buf += txt
print(f"  目录逻辑行 {len(logical)} / 期望 {len(entries)}")
for (cls, hid, title), (lv, bt, bp), (lt, ln) in zip(entries, expected, logical):
    if title != html.unescape(bt) or title != lt:
        mismatch.append((title, f"序位不符 书签={bt} 逻辑行={lt[:40]}"))
        continue
    checked += 1
    if bp != ln:
        mismatch.append((title, f"toc={ln} 书签页={bp}"))
print(f"== 目录页码核对 == 条目 {len(entries)} 已核 {checked} 不一致 {len(mismatch)}")
for m in mismatch[:10]:
    print("  ", m)

# 4. 书眉/页脚抽检：首篇、中段、末页，适配不同篇幅
for pi in sorted({first_body_page - 1, (first_body_page - 1 + n - 1) // 2, n - 1}):
    t = doc[pi].get_text()
    head = t.split("\n")[0:2]
    foot = [x for x in t.split("\n") if re.match(r"^第 \d+ 页$", x.strip())]
    print(f"== p{pi+1} == 脚 {foot} 期望页码 {pi+1} | 书眉在页内: {'AppList 性能全链路解剖' in t}")

# 5. 四态符号计数（PDF vs 源文）
import glob, os
SRC = os.path.dirname(os.path.dirname(PDF))
src_text = ""
for fn in sorted(glob.glob(os.path.join(SRC, "[0-9]*.md"))) + sorted(glob.glob(os.path.join(SRC, "附录*.md"))):
    src_text += open(fn, encoding="utf-8").read()
for s in ["✅", "🟡", "❌", "⬜"]:
    print(f"== 四态 {s} == PDF {full.count(s)} / 源 {src_text.count(s)}")

# 6. 关键数值在 PDF 中
for k in ["34,738,039", "65,629,999", "43.597", "50.928", "24d27dff64c4"]:
    print(f"== 数值 {k} == {'在' if k in full else '缺'}")
doc.close()

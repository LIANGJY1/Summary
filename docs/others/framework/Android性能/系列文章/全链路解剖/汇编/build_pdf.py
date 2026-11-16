#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""AppList 性能全链路解剖 · 18 篇汇编 PDF 构建脚本。

输入：全链路解剖/ 下 00 总纲＋01~16 正文＋附录A（只读）。
输出：汇编/AppList性能全链路解剖-汇编.pdf ＋ combined.html（可复跑源）。
引擎：python-markdown（tables/fenced_code/sane_lists/toc）→ WeasyPrint 70。
纪律：不改原文；外部文件链接（台账/过程记录）去链留文；跨篇链接内化为锚点。
"""
import html
import os
import re
import sys

import markdown
from markdown.extensions.toc import slugify as md_slugify

SRC = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(SRC, "汇编")
CSS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "book.css")

ARTICLES = [
    ("a00", "00-总纲与目录.md"),
    ("a01", "01-virtio_input前端驱动.md"),
    ("a02", "02-input_core核心层.md"),
    ("a03", "03-evdev用户态接口.md"),
    ("a04", "04-EventHub与InputReader读取段.md"),
    ("a05", "05-触摸烹调段.md"),
    ("a06", "06-InputDispatcher入队与裁决.md"),
    ("a07", "07-InputDispatcher分发与ANR.md"),
    ("a08", "08-应用输入消费.md"),
    ("a09", "09-帧调度与RecyclerView布局.md"),
    ("a10", "10-HWUI与RenderThread.md"),
    ("a11", "11-BufferQueue与BLAST.md"),
    ("a12", "12-SurfaceFlinger合成.md"),
    ("a13", "13-virtio_gpu与呈现.md"),
    ("a14", "14-CFS调度与线程状态.md"),
    ("a15", "15-诊断实战全时序归因.md"),
    ("a16", "16-证据复审旧结论逐条审计.md"),
    ("aA", "附录A-Perfetto方法论索引.md"),
]
FILE2SID = {fn: sid for sid, fn in ARTICLES}

COVER = """
<section class="cover">
  <p class="cover-kicker">AAOS13 源码学习 · 性能垂直系列</p>
  <div class="cover-title">AppList 性能全链路解剖</div>
  <p class="cover-subtitle">Android 13 输入 · 渲染 · 呈现全链源码走读与 Perfetto 时序归因</p>
  <hr class="cover-rule">
  <p class="cover-summary">00 总纲＋01~16 正文＋附录A，共 18 篇；内核篇讲工作原理与流程，Android 篇保留源码分析。问题 A：Launcher 主线程
  Sleeping 50.928 ms——S 本身非异常（无帧请求无待输入的正常入睡），异常量＝交付 43.597 ms
  （eventTime→notifyMotion 8.428 ms＋filter 路径 34.738 ms＋发布 0.430 ms，三段闭合）。问题 B：notifyMotion→prepare
  34.738 ms——InputFilter 消费-回注（机制已证，九段里程碑逐 ns 闭合；主线程竞争因果未证）。
  原详细版经 R1~R7 复核；本版已对内核内容分层，详细源码分析另存，不收入主线汇编。</p>
  <p class="cover-meta">初版汇编日期 2026-11-14<br>
  源码基准：kernel 5.15.41（android13-5.15 GKI，HEAD 24d27dff64c4）· Android 13 精树<br>
  证据样本：20261113-151358-vertical-1912967（相对时间口径）· 旧样本 finger.perfetto（BOOTTIME 口径）</p>
  <p class="cover-foot">18 篇汇编 · 双级目录 · 全文书签</p>
</section>
"""


def preprocess(text):
    """围栏感知：行首井号后无空格者（如术语编号 #31 起段的段落）转义，
    防 python-markdown 宽 ATX 正则误判为标题（CommonMark 要求井号后空格）。
    围栏内（#include 等）一律不动。"""
    out, fence = [], False
    for ln in text.split("\n"):
        if ln.startswith("```"):
            fence = not fence
        elif not fence and re.match(r"^#{1,6}[^ \t#]", ln):
            ln = "\\" + ln
        out.append(ln)
    return "\n".join(out)


def make_slugify(prefix):
    def _slug(value, separator):
        return f"{prefix}-{md_slugify(value, separator)}"
    return _slug


def rewrite_links(body, sid, all_ids, misses):
    """跨篇链接内化；外部文件链接去链留文；收集悬空锚点。"""
    def repl(m):
        href, inner = m.group(1), m.group(2)
        if href in FILE2SID:
            return f'<a href="#{FILE2SID[href]}">{inner}</a>'
        if href.startswith(("http://", "https://")):
            return m.group(0)
        if href.startswith("#"):
            target = href[1:]
            if target not in all_ids:
                misses.append((sid, href))
                return f'<a href="#{sid}">{inner}</a>'
            return m.group(0)
        return f'<span class="extref">{inner}</span>'
    return re.sub(r'<a href="([^"]*)">(.*?)</a>', repl, body, flags=re.S)


def _disp_width(text):
    return sum(2 if ord(c) > 0x2E7F else 1 for c in text)


def inject_colgroups(body):
    """table-layout:fixed 下按列内容显示宽度加权注入 colgroup，
    保证宽表恰好 100% 页宽（auto 布局的 min-content 会撑破右界）。"""
    def repl(m):
        table = m.group(1)
        rows = re.findall(r"<tr>(.*?)</tr>", table, flags=re.S)
        ncols, weights, minruns, headers = 0, [], [], []
        for ri, row in enumerate(rows):
            cells = re.findall(r"<t[hd][^>]*>(.*?)</t[hd]>", row, flags=re.S)
            ncols = max(ncols, len(cells))
            for j, cell in enumerate(cells):
                # code 已 break-all 可任意折行，不计入最小宽；plain 长 token 需整放
                plain = html.unescape(re.sub(r"<[^>]+>", "", re.sub(r"<code>.*?</code>", "", cell, flags=re.S)))
                w = min(_disp_width(plain), 48)
                run = max((_disp_width(r) for r in re.findall(r"[^⺀-鿿　-〿＀-￯\s]+", plain)), default=0)
                if j < len(weights):
                    weights[j] = max(weights[j], w)
                    minruns[j] = max(minruns[j], run)
                else:
                    weights.append(w)
                    minruns.append(run)
                if ri == 0:
                    headers.append(_disp_width(plain))
        if ncols == 0:
            return m.group(0)
        weights = [max(w, 6) for w in weights[:ncols]]
        minruns, headers = minruns[:ncols], (headers + [0] * ncols)[:ncols]
        total = sum(weights)
        prop = [w * 100.0 / total for w in weights]
        # 最小列宽（物理 pt）：短 token（≤12 显示宽）保整行×5pt（实测数字 advance 4.99pt），
        # 长标识符/路径允许中途折行；表头至多4字横排×9pt；加 padding10pt+边框+余量≈13pt。
        # A4 内容宽 = 595.28 - 2×18mm(51.02pt) = 493.23pt → 4.9323pt/%
        mins = [max(4.0, min(14.0, (max(min(r, 12) * 5.0, min(hd, 4) * 9.0) + 13) / 4.9323))
                for r, hd in zip(minruns, headers)]
        if sum(mins) > 100.0:  # 极宽表兜底：等比缩到100
            mins = [m * 100.0 / sum(mins) for m in mins]
        pcts = [max(p, mi) for p, mi in zip(prop, mins)]
        over = sum(pcts) - 100.0
        if over > 0:
            flex = [p - mi for p, mi in zip(pcts, mins)]
            tf = sum(flex)
            if tf > 0:
                pcts = [mi + f * (1 - over / tf) for mi, f in zip(mins, flex)]
            else:
                pcts = [p * 100.0 / sum(pcts) for p in pcts]
        pcts = [round(p, 2) for p in pcts]
        pcts[-1] = round(100.0 - sum(pcts[:-1]), 2)
        cols = "".join(f'<col style="width:{p}%">' for p in pcts)
        return f"<table><colgroup>{cols}</colgroup>{table}"
    return re.sub(r"<table>(.*?)</table>", repl, body, flags=re.S)


def toc_entries(tokens, depth=1):
    """取 h1/h2 两级生成目录条目。"""
    out = []
    for tok in tokens:
        lvl = tok["level"]
        if lvl <= 2:
            cls = "toc-l1" if lvl == 1 else "toc-l2"
            out.append(f'<div class="{cls}"><a href="#{tok["id"]}">{tok["name"]}</a></div>')
        if depth < 2:
            out.extend(toc_entries(tok.get("children", []), depth + 1))
    return out


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    with open(CSS, encoding="utf-8") as fh:
        css = fh.read()

    bodies, toc_parts, all_ids = [], [], set()
    per_article = []
    for sid, fn in ARTICLES:
        with open(os.path.join(SRC, fn), encoding="utf-8") as fh:
            text = fh.read()
        md = markdown.Markdown(
            extensions=["tables", "fenced_code", "sane_lists", "toc"],
            extension_configs={"toc": {"slugify": make_slugify(sid), "toc_depth": "1-6"}},
        )
        body = md.convert(preprocess(text))
        ids = set(re.findall(r'id="([^"]+)"', body))
        all_ids |= ids
        per_article.append((sid, fn, len(text.splitlines()), len(md.toc_tokens)))
        bodies.append((sid, body, md.toc_tokens))

    # 第二遍：链接内化（此时 all_ids 已齐）
    misses = []
    final_bodies = []
    for sid, body, tokens in bodies:
        body = rewrite_links(body, sid, all_ids, misses)
        body = inject_colgroups(body)
        final_bodies.append(f'<section class="article" id="{sid}">\n{body}\n</section>')
        toc_parts.extend(toc_entries(tokens))

    head = f"""<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<title>AppList 性能全链路解剖（18 篇汇编）</title>
<meta name="author" content="liang-jy">
<meta name="description" content="Android 13 输入·渲染·呈现全链路源码走读与 Perfetto 时序归因；00 总纲＋01~16 正文＋附录A 共 18 篇汇编；内核原理流程与 Android 源码分析分层阅读。">
<meta name="keywords" content="Android 13, AAOS, AppList, InputDispatcher, InputFilter, SurfaceFlinger, Perfetto, 全链路, 性能">
<style>{css}</style></head><body>
"""
    toc_html = '<nav class="book-toc">\n<h1 id="book-toc">目录</h1>\n' + "\n".join(toc_parts) + "\n</nav>"
    full = head + COVER + toc_html + "\n" + "\n".join(final_bodies) + "\n</body></html>"

    combined = os.path.join(OUT_DIR, "combined.html")
    with open(combined, "w", encoding="utf-8") as fh:
        fh.write(full)

    from weasyprint import HTML
    out_pdf = os.path.join(OUT_DIR, "AppList性能全链路解剖-汇编.pdf")
    HTML(string=full, base_url=SRC).write_pdf(out_pdf)

    import pymupdf
    doc = pymupdf.open(out_pdf)
    meta = dict(doc.metadata)
    meta["creator"] = "markdown 3.10 + WeasyPrint 70.0"
    doc.set_metadata(meta)
    doc.saveIncr()
    outline = doc.get_toc()
    l1 = [(t, p) for lv, t, p in outline if lv == 1]
    print(f"PDF: {out_pdf}")
    print(f"页数: {doc.page_count} | 大小: {os.path.getsize(out_pdf)/1e6:.1f} MB | 书签: {len(outline)}（一级 {len(l1)}）")
    print(f"目录条目: {len(toc_parts)} | 悬空锚点: {len(misses)} {misses[:5]}")
    print("一级书签（标题→PDF页）:")
    for t, p in l1:
        print(f"  p{p:>4}  {t}")
    print("各篇行数/h1数:")
    for sid, fn, nlines, ntok in per_article:
        print(f"  {sid} {fn}: {nlines} 行, {ntok} 个一级标题块")
    doc.close()


if __name__ == "__main__":
    main()

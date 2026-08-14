#!/usr/bin/env python3
"""
uicheck.py — Pixel-level UI element position checker (design vs. device screenshot).

Detects and diffs ALL visible UI elements rendered on a solid background:
cards, banners, panels, buttons, icon blocks, text lines and dividers. It then
measures each element's geometry (x, y, width, height, gaps, margins) and diffs
a UI design reference image against a real device screenshot.

Why this works for the App Store project:
    - Head-unit screens are 1920x1080 at density 1.0, so 1px == 1dp.
    - The UI is RecyclerView grids + XML margins + ItemDecoration spacing.
    - A measured px delta therefore maps 1:1 onto a dp value in layout XML or a
      spacing value in Java (see README.md for the metric -> source map).

No vision model required — detection is done with numpy + Pillow.

Requirements: Python 3, numpy, Pillow.  (No scipy.)

Usage:
    python3 uicheck.py design.png screenshot.png
    python3 uicheck.py design.png screenshot.png --out report.md --json diff.json
    python3 uicheck.py design.png screenshot.png --dilate 8 --min-area 200
    python3 uicheck.py design.png screenshot.png --fail-delta 2

Exit code: 0 normally. Use --fail-delta N to exit 1 when any element's dx/dy/dw/dh
exceeds N px (CI-style gate).
"""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass

import numpy as np
from PIL import Image


# --------------------------------------------------------------------------- #
# Geometry + image helpers
# --------------------------------------------------------------------------- #

@dataclass
class Rect:
    x: int
    y: int
    w: int
    h: int

    @property
    def area(self) -> int:
        return self.w * self.h

    @property
    def right(self) -> int:
        return self.x + self.w - 1

    @property
    def bottom(self) -> int:
        return self.y + self.h - 1


@dataclass
class Element:
    rect: Rect
    kind: str
    fill: float


def load(path: str) -> np.ndarray:
    return np.array(Image.open(path).convert("RGB")).astype(np.int32)


def estimate_background(img: np.ndarray) -> np.ndarray:
    h, w, _ = img.shape
    border = np.concatenate([img[0, :], img[-1, :], img[:, 0], img[:, -1]], axis=0)
    return np.median(border, axis=0).astype(np.int32)


def group_contiguous(indices: np.ndarray, gap: int):
    if len(indices) == 0:
        return []
    runs = []
    start = prev = int(indices[0])
    for i in indices[1:]:
        i = int(i)
        if i - prev > gap:
            runs.append((start, prev))
            start = i
        prev = i
    runs.append((start, prev))
    return runs


def foreground_mask(img: np.ndarray, bg: np.ndarray, tol: int) -> np.ndarray:
    return np.abs(img - bg).max(axis=2) > tol


def horizontal_dilate(mask: np.ndarray, radius: int) -> np.ndarray:
    """Merge nearby foreground pixels along x (joins text glyphs into lines)."""
    if radius <= 0:
        return mask
    out = mask.copy()
    for d in range(1, radius + 1):
        out |= np.roll(mask, d, axis=1)
        out |= np.roll(mask, -d, axis=1)
    return out


# --------------------------------------------------------------------------- #
# Detection
# --------------------------------------------------------------------------- #

def _project_blocks(fg: np.ndarray, min_area: int, min_fill: float,
                    row_gap: int = 2) -> list[Rect]:
    h, w = fg.shape
    min_row_px = max(2, int(w * 0.005))
    min_col_px = max(2, int(h * 0.005))

    row_sum = fg.sum(axis=1)
    rects: list[Rect] = []
    for y0, y1 in group_contiguous(np.where(row_sum >= min_row_px)[0], row_gap):
        band = fg[y0:y1 + 1]
        col_sum = band.sum(axis=0)
        for x0, x1 in group_contiguous(np.where(col_sum >= min_col_px)[0], row_gap):
            rw, rh = x1 - x0 + 1, y1 - y0 + 1
            if rw * rh < min_area:
                continue
            if band[:, x0:x1 + 1].mean() < min_fill:
                continue
            rects.append(Rect(x=int(x0), y=int(y0), w=rw, h=rh))
    rects.sort(key=lambda r: (r.y, r.x))
    return rects


def detect_solid(img: np.ndarray, tol: int, min_area: int, solidity: float) -> list[Rect]:
    """Solid rectangles (cards, banners, buttons, panels, icon blocks)."""
    bg = estimate_background(img)
    fg = foreground_mask(img, bg, tol)
    return _project_blocks(fg, min_area, solidity)


def detect_content(img: np.ndarray, tol: int, min_area: int, dilate: int) -> list[Element]:
    """All content blocks (text lines, icons, buttons, cards, dividers)."""
    bg = estimate_background(img)
    fg = horizontal_dilate(foreground_mask(img, bg, tol), dilate)
    rects = _project_blocks(fg, min_area, min_fill=0.03)
    elems = []
    for r in rects:
        fill = fg[r.y:r.bottom + 1, r.x:r.right + 1].mean()
        elems.append(Element(rect=r, kind=classify(r, img, bg, fill), fill=float(fill)))
    return elems


def classify(r: Rect, img: np.ndarray, bg: np.ndarray, fill: float) -> str:
    """Heuristic element type from shape + fill ratio + position."""
    ih, iw, _ = img.shape
    ar = r.w / max(r.h, 1)
    if r.w > 0.85 * iw and r.h <= 6:
        return "divider"
    if fill < 0.35:
        return "text"
    if r.area >= 200000:
        return "panel"
    if ar > 1.6:
        return "card" if r.area >= 60000 else "button"
    if r.area < 45000:
        return "icon"
    return "button"


def detect_nested(img: np.ndarray, card: Rect, tol: int, min_area: int) -> list[Element]:
    """Detect sub-views (icon/title/desc/button) inside a card.

    Mirrors the app-item layout (item_app.xml): icon = top-left solid block,
    button = bottom-right solid block, text lines = title (top) then desc (below).
    Solid blocks are found first, then masked out so the two text lines split cleanly.
    """
    x0, y0, w, h = card.x, card.y, card.w, card.h
    if w < 60 or h < 60:
        return []
    region = img[y0:y0 + h, x0:x0 + w]
    fill = np.median(region.reshape(-1, 3), axis=0)
    fg = np.abs(region - fill).max(axis=2) > tol

    subs: list[Element] = []
    # solid blocks (icon / button), no dilation
    for r in _project_blocks(fg, min_area, min_fill=0.5, row_gap=2):
        if r.w < 8:
            continue
        rf = float(fg[r.y:r.bottom + 1, r.x:r.right + 1].mean())
        kind = "icon" if (r.x + r.w / 2) < w / 2 else "button"
        subs.append(Element(Rect(x0 + r.x, y0 + r.y, r.w, r.h), kind, rf))

    # text lines: raw pixels, mask solid blocks, tiny dilation only to join glyphs
    text_fg = fg.copy()
    for s in subs:
        text_fg[s.rect.y - y0:s.rect.bottom - y0 + 1,
                s.rect.x - x0:s.rect.right - x0 + 1] = False
    text_fg = horizontal_dilate(text_fg, 2)
    for r in _project_blocks(text_fg, min_area, min_fill=0.03, row_gap=3):
        if r.w < 6 or r.w > 0.7 * w:
            continue
        rf = float(text_fg[r.y:r.bottom + 1, r.x:r.right + 1].mean())
        subs.append(Element(Rect(x0 + r.x, y0 + r.y, r.w, r.h), "text", rf))

    texts = sorted([s for s in subs if s.kind == "text"], key=lambda s: s.rect.y)
    for i, t in enumerate(texts):
        t.kind = "title" if i == 0 else ("desc" if i == 1 else "text")
    return subs


# --------------------------------------------------------------------------- #
# Grid metrics (solid rectangles only)
# --------------------------------------------------------------------------- #

def layout_metrics(rects: list[Rect], img_w: int, img_h: int) -> dict:
    rows: list[list[Rect]] = []
    for r in rects:
        placed = False
        for row in rows:
            ref = row[0]
            if abs(r.y - ref.y) <= max(3, min(r.h, ref.h) // 2):
                row.append(r)
                placed = True
                break
        if not placed:
            rows.append([r])
    rows.sort(key=lambda row: row[0].y)
    for row in rows:
        row.sort(key=lambda r: r.x)

    out_rows = []
    prev_bottom = None
    for row in rows:
        gaps = [b.x - a.right - 1 for a, b in zip(row, row[1:])]
        out_rows.append({
            "y": row[0].y,
            "count": len(row),
            "left_margin": row[0].x,
            "right_margin": img_w - 1 - row[-1].right,
            "gap": gaps,
            "height": row[0].h,
            "row_gap_above": (row[0].y - prev_bottom - 1) if prev_bottom is not None else None,
        })
        prev_bottom = max(r.bottom for r in row)
    return {"width": img_w, "height": img_h, "rect_count": len(rects), "rows": out_rows}


# --------------------------------------------------------------------------- #
# Diff
# --------------------------------------------------------------------------- #

def match_by_proximity(design, actual, tol=40):
    """Match elements by nearest spatial position (Manhattan distance < tol)."""
    matched = []
    used_actual = set()
    used_design = set()
    for i, d in enumerate(design):
        best_j, best_dist = -1, tol
        for j, a in enumerate(actual):
            if j in used_actual:
                continue
            dr, ar = _rect_of(d), _rect_of(a)
            dist = abs(ar.x - dr.x) + abs(ar.y - dr.y)
            if dist < best_dist:
                best_dist, best_j = dist, j
        if best_j >= 0:
            used_actual.add(best_j)
            used_design.add(i)
            matched.append((d, actual[best_j]))
    extra_d = [d for i, d in enumerate(design) if i not in used_design]
    extra_a = [a for j, a in enumerate(actual) if j not in used_actual]
    return matched, extra_d, extra_a


TEXT_KINDS = ("text", "title", "desc")


def _delta_for(kind: str, d_rect: Rect, a_rect: Rect) -> dict:
    """Position-only delta for text views; full delta (incl. size) for others."""
    dx = a_rect.x - d_rect.x
    dy = a_rect.y - d_rect.y
    if kind in TEXT_KINDS:
        return {"dx": dx, "dy": dy, "dw": 0, "dh": 0}
    return {"dx": dx, "dy": dy, "dw": a_rect.w - d_rect.w, "dh": a_rect.h - d_rect.h}


def nested_diffs(design: np.ndarray, actual: np.ndarray,
                 d_cards: list[Rect], a_cards: list[Rect],
                 tol: int, min_area: int) -> list[dict]:
    """Diff sub-views (icon/title/desc/button) between matched design/actual cards."""
    pairs, _, _ = match_by_proximity(d_cards, a_cards)
    out: list[dict] = []
    for dc, ac in pairs:
        d_sub = detect_nested(design, dc, tol, min_area)
        a_sub = detect_nested(actual, ac, tol, min_area)
        for kind in ("icon", "title", "desc", "button", "text"):
            kp, _, _ = match_by_proximity([s for s in d_sub if s.kind == kind],
                                          [s for s in a_sub if s.kind == kind], tol=40)
            for ds, as_ in kp:
                out.append({
                    "kind": kind,
                    "design": {"x": ds.rect.x, "y": ds.rect.y, "w": ds.rect.w, "h": ds.rect.h},
                    "actual": {"x": as_.rect.x, "y": as_.rect.y, "w": as_.rect.w, "h": as_.rect.h},
                    "delta": _delta_for(kind, ds.rect, as_.rect),
                })
    return out


def _rect_of(obj):
    return obj.rect if hasattr(obj, "rect") else obj


def filter_visible(items, img_w: int, img_h: int, ignore_top: int, ignore_bottom: int):
    """Drop elements that sit inside the excluded top/bottom bands (system bars)."""
    if not ignore_top and not ignore_bottom:
        return items
    return [it for it in items
            if _rect_of(it).y >= ignore_top and _rect_of(it).bottom < img_h - ignore_bottom]


def diff_images(design: np.ndarray, actual: np.ndarray, tol: int, min_area: int,
                solidity: float, dilate: int, min_delta: int,
                ignore_top: int = 0, ignore_bottom: int = 0) -> dict:
    dh, dw, _ = design.shape
    ah, aw, _ = actual.shape

    d_solid = detect_solid(design, tol, min_area, solidity)
    a_solid = detect_solid(actual, tol, min_area, solidity)
    d_solid = filter_visible(d_solid, dw, dh, ignore_top, ignore_bottom)
    a_solid = filter_visible(a_solid, aw, ah, ignore_top, ignore_bottom)
    grid = {
        "design": layout_metrics(d_solid, dw, dh),
        "actual": layout_metrics(a_solid, aw, ah),
    }

    d_elems = filter_visible(detect_content(design, tol, min_area, dilate),
                             dw, dh, ignore_top, ignore_bottom)
    a_elems = filter_visible(detect_content(actual, tol, min_area, dilate),
                             aw, ah, ignore_top, ignore_bottom)

    pairs, extra_d, extra_a = match_by_proximity(d_elems, a_elems)
    diffs = []
    for i, (d, a) in enumerate(pairs):
        delta = _delta_for(d.kind, d.rect, a.rect)
        if max(abs(delta[k]) for k in ("dx", "dy", "dw", "dh")) < min_delta:
            continue
        diffs.append({
            "index": i,
            "kind": d.kind,
            "design": {"x": d.rect.x, "y": d.rect.y, "w": d.rect.w, "h": d.rect.h},
            "actual": {"x": a.rect.x, "y": a.rect.y, "w": a.rect.w, "h": a.rect.h},
            "delta": delta,
        })

    return {
        "grid": grid,
        "diffs": diffs,
        "extra_design": [_e(d) for d in extra_d],
        "extra_actual": [_e(a) for a in extra_a],
    }


def _e(el: Element) -> dict:
    return {"kind": el.kind, "x": el.rect.x, "y": el.rect.y,
            "w": el.rect.w, "h": el.rect.h}


# --------------------------------------------------------------------------- #
# Rendering
# --------------------------------------------------------------------------- #

def render_markdown(result: dict) -> str:
    g = result["grid"]
    d, a = g["design"], g["actual"]
    L = ["# uicheck report\n"]
    L.append(f"- design: {d['width']}x{d['height']} ({d['rect_count']} solid rects)")
    L.append(f"- actual: {a['width']}x{a['height']} ({a['rect_count']} solid rects)\n")

    L.append("## All elements diff (px)\n")
    L.append("| # | kind | design x,y,w,h | actual x,y,w,h | dx | dy | dw | dh |")
    L.append("|---|------|----------------|-----------------|----|----|----|----|")
    for df in result["diffs"]:
        de, ae, dl = df["design"], df["actual"], df["delta"]
        L.append(f"| {df['index']} | {df['kind']} | {de['x']},{de['y']},{de['w']},{de['h']} "
                 f"| {ae['x']},{ae['y']},{ae['w']},{ae['h']} "
                 f"| {dl['dx']:+d} | {dl['dy']:+d} | {dl['dw']:+d} | {dl['dh']:+d} |")

    L.append("\n## Grid rows (solid cards)\n")
    L.append("| row | design y/gap↑/gap/h/L/R | actual y/gap↑/gap/h/L/R |")
    L.append("|-----|-------------------------|-------------------------|")
    for i in range(max(len(d["rows"]), len(a["rows"]))):
        dr = d["rows"][i] if i < len(d["rows"]) else None
        ar = a["rows"][i] if i < len(a["rows"]) else None

        def fmt(r):
            if r is None:
                return "-"
            gap = r["row_gap_above"]
            return (f"y={r['y']} gap↑={'-' if gap is None else gap} "
                    f"gap={r['gap']} h={r['height']} L/R={r['left_margin']}/{r['right_margin']}")
        L.append(f"| {i} | {fmt(dr)} | {fmt(ar)} |")

    if result["extra_design"] or result["extra_actual"]:
        L.append("\n## Unmatched elements\n")
        L.append(f"- extra in design: {result['extra_design']}")
        L.append(f"- extra in actual: {result['extra_actual']}")

    return "\n".join(L) + "\n"


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #

def parse_args(argv):
    p = argparse.ArgumentParser(
        description="Pixel-level UI element diff (design vs screenshot), all elements.")
    p.add_argument("design", help="UI design reference image (PNG).")
    p.add_argument("screenshot", help="Device screenshot image (PNG).")
    p.add_argument("--tol", type=int, default=10,
                   help="max channel distance from background considered 'background' (default 10).")
    p.add_argument("--min-area", type=int, default=500,
                   help="minimum element area in px^2 (default 500).")
    p.add_argument("--solidity", type=float, default=0.6,
                   help="min fill ratio for a solid rectangle (default 0.6).")
    p.add_argument("--dilate", type=int, default=8,
                   help="horizontal dilation radius to join text glyphs (default 8).")
    p.add_argument("--min-delta", type=int, default=2,
                   help="only report elements whose position/size differs by >= this many px (default 2).")
    p.add_argument("--ignore-top", type=int, default=72,
                   help="ignore this many px at the top (status bar).")
    p.add_argument("--ignore-bottom", type=int, default=96,
                   help="ignore this many px at the bottom (nav bar).")
    p.add_argument("--out", default=None, help="write markdown report to this file.")
    p.add_argument("--json", dest="json_out", default=None, help="write JSON diff to this file.")
    p.add_argument("--fail-delta", type=int, default=0,
                   help="exit code 1 if any dx/dy/dw/dh exceeds this (0=never fail).")
    return p.parse_args(argv)


def main(argv):
    args = parse_args(argv)
    design = load(args.design)
    actual = load(args.screenshot)

    if design.shape != actual.shape:
        print(f"WARNING: size mismatch design={design.shape} actual={actual.shape}",
              file=sys.stderr)

    result = diff_images(design, actual, args.tol, args.min_area,
                         args.solidity, args.dilate, args.min_delta,
                         args.ignore_top, args.ignore_bottom)

    md = render_markdown(result)
    print(md)

    if args.out:
        with open(args.out, "w") as f:
            f.write(md)
        print(f"[uicheck] report written to {args.out}", file=sys.stderr)
    if args.json_out:
        with open(args.json_out, "w") as f:
            json.dump(result, f, indent=2)
        print(f"[uicheck] json written to {args.json_out}", file=sys.stderr)

    if args.fail_delta > 0:
        for df in result["diffs"]:
            dl = df["delta"]
            if max(abs(dl[k]) for k in ("dx", "dy", "dw", "dh")) > args.fail_delta:
                return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

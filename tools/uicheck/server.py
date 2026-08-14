#!/usr/bin/env python3
"""
server.py — Local web UI for uicheck.

Serves a single-page visualizer that:
  - loads a design reference image and a device screenshot,
  - runs uicheck detection (all elements: cards/buttons/icons/text/panels),
  - renders both images with detected element bounding boxes overlaid,
  - lists misaligned elements with per-axis deltas,
  - lets the user tick the elements to fix and export the selection.

Zero dependencies: Python 3 stdlib only (http.server). Detection reuses uicheck.py.

Usage:
    python3 server.py [--port 8765] [--design /path/design.png] [--shot /path/shot.png]
Then open http://127.0.0.1:8765/ in a browser.

Endpoints:
    GET  /                 -> the visualizer page
    GET  /file?path=<abs>  -> serve an image file (PNG/JPEG)
    POST /analyze          -> {design, screenshot, tol, ...} -> detection JSON
"""

from __future__ import annotations

import argparse
import base64
import io
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

import numpy as np
from PIL import Image

import uicheck

STATIC_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "static")


def decode_b64(data: str) -> np.ndarray:
    raw = base64.b64decode(data)
    return np.array(Image.open(io.BytesIO(raw)).convert("RGB")).astype(np.int32)


def serialize_element(el) -> dict:
    return {"kind": el.kind, "x": el.rect.x, "y": el.rect.y,
            "w": el.rect.w, "h": el.rect.h, "fill": round(el.fill, 3)}


def serialize_solid_with_children(cards, img, tol, min_area) -> list:
    out = []
    for c in cards:
        children = [serialize_element(e) for e in uicheck.detect_nested(img, c, tol, min_area)]
        out.append({"x": c.x, "y": c.y, "w": c.w, "h": c.h, "children": children})
    return out


def analyze(req: dict) -> dict:
    design_path = req.get("design")
    actual_path = req.get("screenshot")
    if not req.get("design_data") and not design_path:
        return {"error": "missing design (path or data)"}
    if not req.get("screenshot_data") and not actual_path:
        return {"error": "missing screenshot (path or data)"}
    tol = int(req.get("tol", 10))
    min_area = int(req.get("min_area", 500))
    solidity = float(req.get("solidity", 0.6))
    dilate = int(req.get("dilate", 8))
    ignore_top = int(req.get("ignore_top", 72))
    ignore_bottom = int(req.get("ignore_bottom", 96))

    design = decode_b64(req["design_data"]) if req.get("design_data") else uicheck.load(design_path)
    actual = decode_b64(req["screenshot_data"]) if req.get("screenshot_data") else uicheck.load(actual_path)
    dw, dh = int(design.shape[1]), int(design.shape[0])
    aw, ah = int(actual.shape[1]), int(actual.shape[0])

    d_elems = uicheck.filter_visible(uicheck.detect_content(design, tol, min_area, dilate),
                                     dw, dh, ignore_top, ignore_bottom)
    a_elems = uicheck.filter_visible(uicheck.detect_content(actual, tol, min_area, dilate),
                                     aw, ah, ignore_top, ignore_bottom)

    d_solid = uicheck.filter_visible(uicheck.detect_solid(design, tol, min_area, solidity),
                                     dw, dh, ignore_top, ignore_bottom)
    a_solid = uicheck.filter_visible(uicheck.detect_solid(actual, tol, min_area, solidity),
                                     aw, ah, ignore_top, ignore_bottom)

    matched, extra_d, extra_a = uicheck.match_by_proximity(d_elems, a_elems)
    diffs = []
    for d, a in matched:
        diffs.append({
            "kind": d.kind,
            "design": {"x": d.rect.x, "y": d.rect.y, "w": d.rect.w, "h": d.rect.h},
            "actual": {"x": a.rect.x, "y": a.rect.y, "w": a.rect.w, "h": a.rect.h},
            "delta": {"dx": a.rect.x - d.rect.x, "dy": a.rect.y - d.rect.y,
                      "dw": a.rect.w - d.rect.w, "dh": a.rect.h - d.rect.h},
        })

    return {
        "design": {"w": dw, "h": dh,
                   "elements": [serialize_element(e) for e in d_elems],
                   "solid": serialize_solid_with_children(d_solid, design, tol, min_area)},
        "actual": {"w": aw, "h": ah,
                   "elements": [serialize_element(e) for e in a_elems],
                   "solid": serialize_solid_with_children(a_solid, actual, tol, min_area)},
        "diffs": diffs,
        "nested_diffs": uicheck.nested_diffs(design, actual, d_solid, a_solid, tol, min_area),
        "extra_design": [serialize_element(e) for e in extra_d],
        "extra_actual": [serialize_element(e) for e in extra_a],
        "grid": {
            "design": uicheck.layout_metrics(d_solid, dw, dh),
            "actual": uicheck.layout_metrics(a_solid, aw, ah),
        },
    }


class Handler(BaseHTTPRequestHandler):
    def _send(self, code: int, body: bytes, ctype: str = "application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/":
            return self._serve_file(os.path.join(STATIC_DIR, "index.html"), "text/html")
        if parsed.path == "/file":
            path = parse_qs(parsed.query).get("path", [""])[0]
            return self._serve_file(path, "image/png")
        self._send(404, b"not found", "text/plain")

    def do_POST(self):
        parsed = urlparse(self.path)
        if parsed.path == "/analyze":
            length = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(length) or b"{}")
            self._send(200, json.dumps(analyze(req)).encode())
            return
        self._send(404, b"not found", "text/plain")

    def _serve_file(self, path: str, ctype: str):
        try:
            with open(path, "rb") as f:
                data = f.read()
        except OSError as e:
            self._send(404, str(e).encode(), "text/plain")
            return
        low = path.lower()
        if low.endswith(".png"):
            ctype = "image/png"
        elif low.endswith((".jpg", ".jpeg")):
            ctype = "image/jpeg"
        elif low.endswith(".html"):
            ctype = "text/html"
        self._send(200, data, ctype)

    def log_message(self, fmt, *args):
        sys.stderr.write("[server] " + fmt % args + "\n")


def main(argv):
    p = argparse.ArgumentParser(description="uicheck local web UI.")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--design", default="", help="default design image path.")
    p.add_argument("--shot", default="", help="default screenshot path.")
    args = p.parse_args(argv)

    print(f"uicheck server on http://127.0.0.1:{args.port}/")
    if args.design:
        print(f"  design default: {args.design}")
    if args.shot:
        print(f"  shot   default: {args.shot}")

    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main(sys.argv[1:])

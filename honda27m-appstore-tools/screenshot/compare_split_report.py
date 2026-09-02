#!/usr/bin/env python3
"""
分屏 UI图 ↔ 实机图 配对并排 HTML 报告生成器（独立脚本）。

按三位序号自动配对 分屏UE设计稿 与 车机实拍截图，生成自包含的并排对比页面
（含像素级差异高亮），供 分屏UI走查 肉眼比对使用。零第三方依赖
（PIL 可选：缺失时退化为纯并排、无差异列）。

完全独立于全屏脚本（compare_report.py / capture_appstore_screenshots.py）：
    模式配置：跟随 capture_appstore_splitscreenshots.py 顶部 CURRENT_VARIANT（分屏独立配置）
    实机图：capture_appstore_splitscreenshots.py 的默认输出 screenshots/<模式>_split/
    UI图：  模式名映射到 分屏<cn|en>_<D|L>（模式名中的 fullscreen 视作 split）
    报告：  report/<模式>_split/index.html

用法（默认跟随 capture_appstore_splitscreenshots.py 顶部 CURRENT_VARIANT）：
    python3 honda27m-appstore-tools/screenshot/compare_split_report.py

可选覆盖（不影响截图脚本配置）：
    python3 honda27m-appstore-tools/screenshot/compare_split_report.py --variant zh_day_split
    python3 honda27m-appstore-tools/screenshot/compare_split_report.py --ref-dir <UI图目录> --actual-dir <实机图目录> --out <html路径>
"""

import argparse
import re
import shutil
import time
from pathlib import Path

# 像素级比对：尝试使用 PIL，若不可用则回退为并排
try:
    from PIL import Image, ImageChops
    HAS_PIL = True
except Exception:
    HAS_PIL = False

# 与分屏截图脚本共用配置：读取其顶部 CURRENT_VARIANT / resolve_output_dir /
# get_mode_ref_dir。分屏模式配置独立维护在 capture_appstore_splitscreenshots.py
# 内，不依赖全屏脚本 capture_appstore_screenshots.py。
try:
    import capture_appstore_splitscreenshots as _split_cfg
except Exception:
    _split_cfg = None


def get_current_variant() -> str:
    """当前模式名：跟随分屏截图脚本顶部 CURRENT_VARIANT。"""
    return (getattr(_split_cfg, "CURRENT_VARIANT", "") or "").strip()


def to_split_form(variant: str) -> str:
    """模式名的形态归一为 split：zh_day_fullscreen → zh_day_split（已是 split 则原样）。"""
    parts = variant.split("_")
    if len(parts) == 3 and parts[2] == "fullscreen":
        return "_".join(parts[:2] + ["split"])
    return variant


def default_actual_dir(variant: str) -> Path:
    """默认实机图目录：分屏截图脚本的 resolve_output_dir 直接指向 screenshots/<模式>_split。"""
    return Path(_split_cfg.resolve_output_dir(None, variant))


def default_ref_dir(variant: str) -> Path:
    """默认 UI图目录：模式名（形态归一为 split）按 <语言>_<昼夜>_split 映射 分屏<cn|en>_<D|L>。"""
    resolved = _split_cfg.get_mode_ref_dir(to_split_form(variant))
    if resolved:
        return Path(resolved)
    return Path(__file__).with_name("screenshots")


def default_out_path(variant: str) -> Path:
    """报告输出：report/<模式>_split/index.html，与全屏版报告互不覆盖。"""
    return Path(__file__).with_name("report") / f"{variant}_split" / "index.html"


INDEX_RE = re.compile(r"^(\d{3})_")

STATUS_LABEL = {
    "both": "✅ 已配对",
    "missing_capture": "⬜ 未截",
    "orphan_capture": "❓ 多出（UI图无此序号）",
}


def scan_indexed(directory: Path) -> dict:
    """返回 {序号: 文件路径}，取每个序号最先匹配的一个。"""
    found = {}
    if not directory.exists():
        return found
    for p in sorted(directory.rglob("*.png")):
        m = INDEX_RE.match(p.name)
        if m:
            found.setdefault(m.group(1), p)
    return found


def copy_asset(src: Path, assets_dir: Path, new_name: str) -> str:
    dest = assets_dir / new_name
    if not dest.parent.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
    if not dest.exists() or dest.stat().st_mtime < src.stat().st_mtime:
        shutil.copy2(src, dest)
    return dest.name


def build_diff(ref: Path, cap: Path, assets_dir: Path, index: str):
    """像素级 diff：生成高亮差异图，返回 (文件名, 差异率%)。"""
    if not HAS_PIL or ref is None or cap is None:
        return None, None
    try:
        a = Image.open(ref).convert("RGB")
        b = Image.open(cap).convert("RGB")
        if a.size != b.size:
            b = b.resize(a.size, Image.BILINEAR)
        diff = ImageChops.difference(a, b)
        gray = diff.convert("L")
        bw = gray.point(lambda p: 255 if p > 15 else 0)
        w, h = bw.size
        total = w * h
        diff_pixels = sum(1 for p in bw.getdata() if p > 0)
        ratio = diff_pixels / total * 100 if total else 0
        overlay = Image.new("RGB", a.size, (255, 0, 0))
        mask = bw.convert("L")
        highlighted = Image.composite(overlay, a, mask)
        blended = Image.blend(a, highlighted, 0.5)
        out_name = f"{index}_diff.png"
        out_path = assets_dir / out_name
        blended.save(out_path)
        return out_name, round(ratio, 2)
    except Exception:
        return None, None


def build_rows(refs: dict, caps: dict, assets_dir: Path) -> list:
    rows = []
    for index in sorted(set(refs) | set(caps)):
        ref, cap = refs.get(index), caps.get(index)
        ref_name = copy_asset(ref, assets_dir, f"{index}_ref.png") if ref else None
        cap_name = copy_asset(cap, assets_dir, f"{index}_cap.png") if cap else None
        diff_name, diff_ratio = build_diff(ref, cap, assets_dir, index) if ref and cap else (None, None)
        if ref and cap:
            status = "both"
        elif ref:
            status = "missing_capture"
        else:
            status = "orphan_capture"
        title = (ref or cap).stem
        rows.append({"index": index, "title": title, "status": status,
                     "ref": ref_name, "cap": cap_name, "diff": diff_name, "ratio": diff_ratio})
    return rows


HTML_TEMPLATE = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="utf-8">
<title>分屏UI走查 并排对比报告（含像素级差异）</title>
<style>
  body {{ font-family: sans-serif; background:#1e1e1e; color:#ddd; margin:20px; }}
  h1 {{ font-size:20px; }} .meta {{ color:#888; font-size:13px; margin-bottom:16px; }}
  .row {{ background:#2a2a2a; border-radius:8px; padding:12px; margin-bottom:18px; }}
  .head {{ display:flex; justify-content:space-between; align-items:center; margin-bottom:8px; flex-wrap:wrap; gap:8px; }}
  .idx {{ font-weight:bold; font-size:15px; }}
  .badge {{ font-size:12px; padding:2px 10px; border-radius:10px; background:#444; }}
  .badge.missing {{ background:#7a5c00; }} .badge.orphan {{ background:#7a2020; }}
  .ratio {{ font-size:12px; padding:2px 8px; border-radius:8px; background:#333; color:#ffb; }}
  .ratio.high {{ background:#7a2020; color:#fff; }} .ratio.low {{ background:#204a20; }}
  .imgs {{ display:flex; gap:10px; flex-wrap:wrap; }}
  .pane {{ flex:1; text-align:center; min-width:0; }}
  .pane img {{ width:100%; height:auto; border:1px solid #555; border-radius:4px; background:#000; cursor:zoom-in; }}
  .pane.missing img {{ display:none; }}
  .pane.missing::after {{ content:"（无图）"; color:#666; display:block; padding:80px 0; }}
  .label {{ font-size:12px; color:#aaa; margin-bottom:4px; display:flex; align-items:center; justify-content:center; gap:6px; }}
  .label .copy {{ font-size:11px; color:#8cf; cursor:pointer; border:1px solid #446; background:#2a3a4a; padding:1px 6px; border-radius:8px; }}
  .label .copy.ok {{ color:#0f0; border-color:#2a5a2a; background:#1a2a1a; }}
  .diff {{ flex:1; text-align:center; min-width:0; }}
  .diff img {{ width:100%; height:auto; border:1px solid #a33; border-radius:4px; background:#000; cursor:zoom-in; }}
  /* 放大遮罩 - PS 风格像素级查看 + 网格 + 探针 */
  #lightbox {{ display:none; position:fixed; inset:0; background:rgba(0,0,0,0.92); z-index:9999; overflow:hidden; padding:0; }}
  #lightbox.show {{ display:block; }}
  #lightbox .lb-viewport {{ position:absolute; inset:0; overflow:hidden; cursor:grab; display:flex; align-items:center; justify-content:center; }}
  #lightbox .lb-viewport.grabbing {{ cursor:grabbing; }}
  #lightbox .lb-stage {{ position:relative; transform-origin:0 0; will-change:transform; }}
  #lightbox .lb-stage img {{ display:block; max-width:none; max-height:none; width:auto; height:auto; image-rendering:pixelated; image-rendering:crisp-edges; border:2px solid #666; background:#000; box-shadow:0 0 20px rgba(0,0,0,0.8); }}
  #lightbox .lb-stage img.pixels {{ image-rendering:pixelated; }}
  #lightbox .lb-grid {{ position:absolute; inset:2px; pointer-events:none; opacity:0; transition:opacity 0.15s; background-size:calc(100% / var(--w)) calc(100% / var(--h)); background-image: linear-gradient(to right, rgba(255,255,255,0.18) 1px, transparent 1px), linear-gradient(to bottom, rgba(255,255,255,0.18) 1px, transparent 1px); }}
  #lightbox .lb-grid.on {{ opacity:1; }}
  #lightbox .lb-bar {{ position:absolute; top:12px; left:50%; transform:translateX(-50%); background:rgba(30,30,30,0.9); color:#ccc; font-size:12px; padding:6px 14px; border-radius:20px; border:1px solid #444; z-index:2; pointer-events:none; white-space:nowrap; }}
  #lightbox .lb-bar b {{ color:#fff; }}
  #lightbox .lb-hint {{ position:absolute; bottom:14px; left:50%; transform:translateX(-50%); background:rgba(30,30,30,0.85); color:#888; font-size:11px; padding:5px 12px; border-radius:16px; border:1px solid #333; z-index:2; pointer-events:none; }}
  #lightbox .lb-zoom {{ position:absolute; top:14px; right:16px; background:rgba(30,30,30,0.9); color:#ffb; font-size:12px; padding:4px 10px; border-radius:10px; border:1px solid #444; z-index:2; pointer-events:none; }}
  #lightbox .lb-probe {{ position:absolute; width:160px; height:160px; border:2px solid #fff; border-radius:8px; overflow:hidden; pointer-events:none; display:none; z-index:2; box-shadow:0 4px 20px rgba(0,0,0,0.9); background:#000; }}
  #lightbox .lb-probe.on {{ display:block; }}
  #lightbox .lb-probe canvas {{ width:100%; height:100%; image-rendering:pixelated; }}
  #lightbox .lb-probe .cross {{ position:absolute; left:50%; top:50%; width:20px; height:20px; transform:translate(-50%,-50%); pointer-events:none; }}
  #lightbox .lb-probe .cross::before, #lightbox .lb-probe .cross::after {{ content:""; position:absolute; background:rgba(255,255,0,0.9); }}
  #lightbox .lb-probe .cross::before {{ left:50%; top:0; width:1px; height:100%; }}
  #lightbox .lb-probe .cross::after {{ top:50%; left:0; width:100%; height:1px; }}
  #lightbox .lb-coords {{ position:absolute; bottom:44px; left:50%; transform:translateX(-50%); background:rgba(20,20,20,0.95); color:#0f0; font:11px/1.4 monospace; padding:6px 10px; border-radius:8px; border:1px solid #333; z-index:2; pointer-events:none; display:none; white-space:nowrap; }}
  #lightbox .lb-coords.on {{ display:block; }}
</style>
</head>
<body>
<h1>分屏UI走查 并排对比报告（含像素级差异）</h1>
<div class="meta">{summary}</div>
<div style="font-size:12px;color:#888;margin-bottom:12px;">点击任意图片 1:1 放大 · 拖拽平移 · Ctrl+滚轮像素级缩放</div>
{rows}
<div id="lightbox"><div class="lb-bar" id="lbTitle"></div><div class="lb-zoom" id="lbZoom">100%</div><div class="lb-viewport" id="lbViewport"><div class="lb-stage" id="lbStage"><img id="lbImg"><div class="lb-grid" id="lbGrid"></div></div></div><div class="lb-coords" id="lbCoords"></div><div class="lb-probe" id="lbProbe"><canvas id="lbProbeCanvas" width="160" height="160"></canvas><div class="cross"></div></div><div class="lb-hint">拖拽平移 · Ctrl+滚轮缩放 · 悬停看探针 · 单击遮罩关闭 · 双击重置</div></div>
<script>
(function(){{
const lb=document.getElementById('lightbox'), vp=document.getElementById('lbViewport'), stage=document.getElementById('lbStage'), lbImg=document.getElementById('lbImg'), grid=document.getElementById('lbGrid'), probe=document.getElementById('lbProbe'), canvas=document.getElementById('lbProbeCanvas'), coords=document.getElementById('lbCoords');
let scale=1, tx=0, ty=0, dragging=false, sx=0, sy=0, baseTx=0, baseTy=0, natW=0, natH=0, dpr=window.devicePixelRatio||1;
const ctx=canvas.getContext('2d');
function update(){{ stage.style.transform='translate('+tx+'px,'+ty+'px) scale('+scale+')'; document.getElementById('lbZoom').textContent=Math.round(scale*100)+'% ('+Math.round(scale*dpr*10)/10+'×物理)'; lbImg.classList.toggle('pixels', scale>=2); if(natW){{ grid.style.setProperty('--w', natW); grid.style.setProperty('--h', natH); }} grid.classList.toggle('on', scale>=3); coords.classList.toggle('on', scale>=2); probe.classList.toggle('on', scale>=2); }}
function reset(){{ scale=1; tx=0; ty=0; update(); }}
function showProbe(clientX, clientY){{ if(scale<2||!natW) return; const rect=lbImg.getBoundingClientRect(); const ix=(clientX - rect.left)/scale, iy=(clientY - rect.top)/scale; if(ix<0||iy<0||ix>=natW||iy>=natH) {{ probe.classList.remove('on'); coords.classList.remove('on'); return; }} probe.style.left=(clientX+18)+'px'; probe.style.top=(clientY+18)+'px'; const s=9, z=10; canvas.width=160; canvas.height=160; ctx.imageSmoothingEnabled=false; ctx.clearRect(0,0,160,160); ctx.drawImage(lbImg, Math.floor(ix)-s, Math.floor(iy)-s, s*2+1, s*2+1, 0,0,160,160); // 网格
  ctx.strokeStyle='rgba(255,255,255,0.22)'; ctx.lineWidth=1; for(let i=1;i<=s*2;i++){{ const p=i*160/(s*2+1); ctx.beginPath(); ctx.moveTo(p,0); ctx.lineTo(p,160); ctx.stroke(); ctx.beginPath(); ctx.moveTo(0,p); ctx.lineTo(160,p); ctx.stroke(); }}
  coords.textContent='x:'+Math.floor(ix)+' y:'+Math.floor(iy)+' ｜ 偏移探针 '+ (s*2+1)+'×'+(s*2+1)+' ｜ dpr='+dpr;
}}
document.querySelectorAll('.pane img, .diff img').forEach(img=>{{
  img.addEventListener('click', e=>{{
    e.stopPropagation();
    const title=img.closest('.row')?.querySelector('.idx')?.textContent || '';
    const label=img.closest('.pane,.diff')?.querySelector('.label')?.textContent || '';
    lbImg.onload=()=>{{ natW=lbImg.naturalWidth; natH=lbImg.naturalHeight; lbImg.style.width=natW+'px'; lbImg.style.height=natH+'px'; }};
    lbImg.src=img.src;
    if(lbImg.complete){{ natW=lbImg.naturalWidth; natH=lbImg.naturalHeight; lbImg.style.width=natW+'px'; lbImg.style.height=natH+'px'; }}
    document.getElementById('lbTitle').innerHTML='<b>'+title+'</b> — '+label+' ｜ '+img.src.split('/').pop()+' ｜ dpr='+dpr;
    reset(); lb.classList.add('show');
  }});
}});
// 拖拽平移
vp.addEventListener('mousedown', e=>{{ if(e.target.closest('.lb-probe,.lb-bar,.lb-zoom,.lb-hint,.lb-coords')) return; dragging=true; vp.classList.add('grabbing'); sx=e.clientX; sy=e.clientY; baseTx=tx; baseTy=ty; e.preventDefault(); }});
window.addEventListener('mousemove', e=>{{ if(dragging){{ tx=baseTx + (e.clientX - sx); ty=baseTy + (e.clientY - sy); update(); }} else if(lb.classList.contains('show')) showProbe(e.clientX, e.clientY); }});
window.addEventListener('mouseup', ()=>{{ dragging=false; vp.classList.remove('grabbing'); }});
vp.addEventListener('touchstart', e=>{{ if(e.touches.length!==1) return; dragging=true; sx=e.touches[0].clientX; sy=e.touches[0].clientY; baseTx=tx; baseTy=ty; }}, {{passive:true}});
window.addEventListener('touchmove', e=>{{ if(!dragging||e.touches.length!==1) return; tx=baseTx + (e.touches[0].clientX - sx); ty=baseTy + (e.touches[0].clientY - sy); update(); }}, {{passive:true}});
window.addEventListener('touchend', ()=>{{ dragging=false; }});
vp.addEventListener('mousemove', e=>{{ if(!dragging) showProbe(e.clientX, e.clientY); }});
vp.addEventListener('mouseleave', ()=>{{ probe.classList.remove('on'); coords.classList.remove('on'); }});
// Ctrl+滚轮像素级缩放（PS 式），无 Ctrl 时滚轮为普通滚动
lb.addEventListener('wheel', e=>{{
  if(!e.ctrlKey) return;
  e.preventDefault();
  const rect=vp.getBoundingClientRect();
  const cx=e.clientX - rect.left - rect.width/2, cy=e.clientY - rect.top - rect.height/2;
  const factor=e.deltaY<0?1.15:0.87;
  const ns=Math.min(8, Math.max(0.5, scale*factor));
  tx = cx - (cx - tx) * (ns/scale);
  ty = cy - (cy - ty) * (ns/scale);
  scale=ns; update();
}}, {{passive:false}});
// 单击遮罩关闭（点击图片本身不关闭）
lb.addEventListener('click', e=>{{ if(e.target===lb || e.target===vp) lb.classList.remove('show'); }});
lbImg.addEventListener('dblclick', e=>{{ e.stopPropagation(); reset(); }});
document.addEventListener('keydown', e=>{{ if(e.key==='Escape') lb.classList.remove('show'); }});
// 复制原图 & 拖拽至 PS
document.querySelectorAll('.copy').forEach(btn=>{{
  btn.addEventListener('click', async e=>{{
    e.stopPropagation();
    const src=btn.getAttribute('data-src');
    const url=new URL(src, location.href).href;
    try{{
      const resp=await fetch(url); const blob=await resp.blob();
      await navigator.clipboard.write([new ClipboardItem({{[blob.type]: blob}})]);
      btn.textContent='已复制图片'; btn.classList.add('ok');
    }}catch(_){{
      try{{ await navigator.clipboard.writeText(url); btn.textContent='已复制链接'; btn.classList.add('ok'); }}catch(__){{ prompt('复制原图链接', url); }}
    }}
    setTimeout(()=>{{ btn.textContent='复制原图'; btn.classList.remove('ok'); }}, 1600);
  }});
}});
document.querySelectorAll('.pane img, .diff img').forEach(img=>{{
  img.addEventListener('dragstart', e=>{{ e.dataTransfer.setData('text/uri-list', img.src); e.dataTransfer.setData('text/plain', img.src); }});
}});

}})();
</script>
</body>
</html>
"""

ROW_TEMPLATE = """
<div class="row">
  <div class="head">
    <span class="idx">{index} · {title}</span>
    <span class="badge {status_class}">{status_label}</span>
    {ratio_badge}
  </div>
  <div class="imgs">
    <div class="pane {ref_class}">
      <div class="label">UI图（分屏设计稿） <span class="copy" data-src="assets/{ref}" title="复制原图链接，拖拽图片可直接至 PS">复制原图</span> · <a href="assets/{ref}" download style="color:#8cf;font-size:11px;">下载</a></div>
      <img src="assets/{ref}?v={v}" loading="lazy" draggable="true" title="拖拽至 PS / 右键复制原图">
    </div>
    <div class="pane {cap_class}">
      <div class="label">实机图（车机分屏截屏） <span class="copy" data-src="assets/{cap}" title="复制原图链接，拖拽图片可直接至 PS">复制原图</span> · <a href="assets/{cap}" download style="color:#8cf;font-size:11px;">下载</a></div>
      <img src="assets/{cap}?v={v}" loading="lazy" draggable="true" title="拖拽至 PS / 右键复制原图">
    </div>
    {diff_pane}
  </div>
</div>
"""


def render(rows: list, out_path: Path, ref_dir: Path, actual_dir: Path, variant: str = "") -> None:
    n_both = sum(1 for r in rows if r["status"] == "both")
    n_missing = sum(1 for r in rows if r["status"] == "missing_capture")
    n_orphan = sum(1 for r in rows if r["status"] == "orphan_capture")
    summary = (f"共 {len(rows)} 个序号 ｜ 已配对 {n_both} ｜ 未截 {n_missing} ｜ 多出 {n_orphan}"
               f"<br>模式：{variant}<br>UI图目录：{ref_dir}<br>实机图目录：{actual_dir}<br>差异率 = 差异像素 / 总像素×100，阈值 15")
    v = time.strftime("%Y%m%d%H%M%S")  # 缓存破坏：同路径图片刷新后浏览器不再用旧缓存
    parts = []
    for r in rows:
        ratio = r.get("ratio")
        diff = r.get("diff")
        if ratio is not None:
            cls = "high" if ratio > 5 else "low" if ratio < 1 else ""
            ratio_badge = f'<span class="ratio {cls}">差异 {ratio}%</span>'
        else:
            ratio_badge = '<span class="ratio">差异 --</span>' if r["status"] == "both" else ""
        if diff:
            diff_pane = f'<div class="diff"><div class="label">差异高亮（红） <span class="copy" data-src="assets/{diff}" title="复制原图">复制原图</span> · <a href="assets/{diff}" download style="color:#8cf;font-size:11px;">下载</a></div><img src="assets/{diff}?v={v}" loading="lazy" draggable="true" title="拖拽至 PS"></div>'
        else:
            diff_pane = ""
        parts.append(ROW_TEMPLATE.format(
            index=r["index"], title=r["title"],
            status_class={"missing_capture": "missing", "orphan_capture": "orphan"}.get(r["status"], ""),
            status_label=STATUS_LABEL[r["status"]],
            ref=r["ref"] or "x", cap=r["cap"] or "x", v=v,
            ref_class="" if r["ref"] else "missing",
            cap_class="" if r["cap"] else "missing",
            ratio_badge=ratio_badge,
            diff_pane=diff_pane,
        ))
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(HTML_TEMPLATE.format(summary=summary, rows="\n".join(parts)), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description="分屏 UI图↔实机图 并排对比报告生成器（默认跟随分屏截图脚本顶部 CURRENT_VARIANT，实机图取 <模式>_split）")
    parser.add_argument("--variant", "-V", default=None,
                        help=f"模式名（默认跟随分屏截图脚本: {get_current_variant()}）；UI图按其分屏形态映射，实机图取 screenshots/<模式>_split")
    parser.add_argument("--ref-dir", type=Path, default=None,
                        help="UI图目录（默认按模式名映射 分屏<cn|en>_<D|L>）")
    parser.add_argument("--actual-dir", type=Path, default=None,
                        help="实机图目录，含分类子目录（默认: screenshots/<模式>_split）")
    parser.add_argument("--out", type=Path, default=None,
                        help="输出 HTML 路径（默认: report/<模式>_split/index.html）")
    args = parser.parse_args()

    variant = (args.variant or get_current_variant()).strip()
    if not variant:
        raise SystemExit("错误：无法识别模式（分屏截图脚本 CURRENT_VARIANT 为空），请用 --variant 指定。")

    ref_dir = args.ref_dir or default_ref_dir(variant)
    actual_dir = args.actual_dir or default_actual_dir(variant)
    out_path = args.out or default_out_path(variant)

    refs = scan_indexed(ref_dir)
    caps = scan_indexed(actual_dir)
    if not refs:
        raise SystemExit(f"错误：UI图目录无可用图片：{ref_dir}")
    if not caps:
        raise SystemExit(f"错误：实机图目录无可用图片：{actual_dir}（先运行 capture_appstore_splitscreenshots.py）")

    assets_dir = out_path.parent / "assets"
    rows = build_rows(refs, caps, assets_dir)
    render(rows, out_path, ref_dir, actual_dir, variant)
    print(f"模式: {variant}（UI图按分屏形态映射: {to_split_form(variant)}）")
    print(f"UI图: {ref_dir}")
    print(f"实机图: {actual_dir}")
    print(f"报告已生成：{out_path.resolve()}（配对 {sum(1 for r in rows if r['status'] == 'both')}/{len(rows)}）")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Reusable Excel SRS workbook utility.

Standard-library only. Provides three operations:
inspect, apply, validate.
"""

from __future__ import annotations

import argparse
import json
import re
import shutil
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
import xml.etree.ElementTree as ET


NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
XML_NS = "http://www.w3.org/XML/1998/namespace"

ET.register_namespace("", NS)
ET.register_namespace("r", REL_NS)


def qn(tag: str) -> str:
    return f"{{{NS}}}{tag}"


@dataclass(frozen=True)
class BundlePaths:
    base_dir: Path
    template_rule: Path
    workflow_rule: Path

    @property
    def default_template(self) -> Path:
        text = self.template_rule.read_text(encoding="utf-8")
        match = re.search(r"^- Template workbook:\s*(.+)$", text, re.M)
        if not match:
            raise RuntimeError(f"template workbook path not found in {self.template_rule}")
        raw = match.group(1).strip()
        raw = raw.strip("`\"'")
        return Path(raw)


def bundle_paths() -> BundlePaths:
    base = Path(__file__).resolve().parents[1]
    return BundlePaths(
        base_dir=base,
        template_rule=base / "rules" / "excel-template-rules.md",
        workflow_rule=base / "rules" / "srs-workflow-rules.md",
    )


def read_shared_strings(zf: zipfile.ZipFile) -> list[str]:
    if "xl/sharedStrings.xml" not in zf.namelist():
        return []
    root = ET.fromstring(zf.read("xl/sharedStrings.xml"))
    return ["".join(node.itertext()) for node in root]


def cell_text(cell: ET.Element, shared: list[str]) -> str:
    kind = cell.attrib.get("t")
    if kind == "s":
        value = cell.find(qn("v"))
        return shared[int(value.text)] if value is not None and value.text is not None else ""
    if kind == "inlineStr":
        is_el = cell.find(qn("is"))
        return "".join(is_el.itertext()) if is_el is not None else ""
    value = cell.find(qn("v"))
    return value.text if value is not None and value.text is not None else ""


def set_inline_text(cell: ET.Element, text: str) -> None:
    for child in list(cell):
        cell.remove(child)
    cell.attrib["t"] = "inlineStr"
    is_el = ET.SubElement(cell, qn("is"))
    t_el = ET.SubElement(is_el, qn("t"))
    t_el.set(f"{{{XML_NS}}}space", "preserve")
    t_el.text = text


def col_to_index(col: str) -> int:
    idx = 0
    for ch in col.upper():
        if not ch.isalpha():
            break
        idx = idx * 26 + (ord(ch) - 64)
    return idx


def index_to_col(idx: int) -> str:
    if idx < 1:
        raise ValueError("column index must be >= 1")
    out = []
    while idx:
        idx, rem = divmod(idx - 1, 26)
        out.append(chr(65 + rem))
    return "".join(reversed(out))


def parse_ref(ref: str) -> tuple[int, int]:
    m = re.fullmatch(r"([A-Z]+)(\d+)", ref.upper())
    if not m:
        raise ValueError(f"invalid cell ref: {ref}")
    return col_to_index(m.group(1)), int(m.group(2))


def build_cell_map(root: ET.Element) -> dict[str, ET.Element]:
    cells: dict[str, ET.Element] = {}
    for row in root.find(qn("sheetData")) or []:
        for cell in row.findall(qn("c")):
            cells[cell.attrib["r"]] = cell
    return cells


def workbook_sheet_names(zf: zipfile.ZipFile) -> list[str]:
    wb = ET.fromstring(zf.read("xl/workbook.xml"))
    return [sheet.attrib["name"] for sheet in wb.find(qn("sheets")) or []]


def workbook_sheet_paths(zf: zipfile.ZipFile) -> dict[str, str]:
    wb = ET.fromstring(zf.read("xl/workbook.xml"))
    rels = ET.fromstring(zf.read("xl/_rels/workbook.xml.rels"))
    relmap = {rel.attrib["Id"]: rel.attrib["Target"] for rel in rels}
    paths: dict[str, str] = {}
    for sheet in wb.find(qn("sheets")) or []:
        rid = sheet.attrib[f"{{{REL_NS}}}id"]
        target = relmap[rid]
        paths[sheet.attrib["name"]] = target if target.startswith("xl/") else f"xl/{target}"
    return paths


def load_sheet_root(zf: zipfile.ZipFile, sheet_path: str) -> ET.Element:
    return ET.fromstring(zf.read(sheet_path))


def sheet_meta(root: ET.Element, shared: list[str]) -> dict[str, object]:
    dim = root.find(qn("dimension"))
    merges = root.find(qn("mergeCells"))
    validations = root.find(qn("dataValidations"))
    protection = root.find(qn("sheetProtection"))
    rows = root.find(qn("sheetData")) or []
    first_rows = []
    for row in list(rows)[:12]:
        values = []
        for cell in row.findall(qn("c")):
            text = cell_text(cell, shared)
            if text:
                values.append(f"{cell.attrib['r']}={text}")
        if values:
            first_rows.append(" | ".join(values))
    return {
        "dimension": dim.attrib.get("ref") if dim is not None else None,
        "merged_count": len(list(merges)) if merges is not None else 0,
        "validation_count": int(validations.attrib.get("count", "0")) if validations is not None else 0,
        "protected": bool(protection is not None and protection.attrib),
        "sample_rows": first_rows,
    }


def inspect_workbook(path: Path) -> int:
    with zipfile.ZipFile(path) as zf:
        shared = read_shared_strings(zf)
        print(f"Workbook: {path}")
        print("Sheets:")
        for name in workbook_sheet_names(zf):
            meta = sheet_meta(load_sheet_root(zf, workbook_sheet_paths(zf)[name]), shared)
            print(f"- {name}")
            print(f"  dimension: {meta['dimension']}")
            print(f"  merged: {meta['merged_count']}")
            print(f"  validations: {meta['validation_count']}")
            print(f"  protected: {meta['protected']}")
        return 0


def parse_patch_file(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def ensure_merge_parent(root: ET.Element) -> ET.Element:
    merge_parent = root.find(qn("mergeCells"))
    if merge_parent is not None:
        return merge_parent
    merge_parent = ET.Element(qn("mergeCells"), count="0")
    sheet_data = root.find(qn("sheetData"))
    if sheet_data is None:
        root.append(merge_parent)
        return merge_parent
    children = list(root)
    index = children.index(sheet_data)
    root.insert(index + 1, merge_parent)
    return merge_parent


def add_merge_ref(root: ET.Element, ref: str) -> None:
    merge_parent = ensure_merge_parent(root)
    existing = {m.attrib.get("ref") for m in merge_parent.findall(qn("mergeCell"))}
    if ref in existing:
        return
    ET.SubElement(merge_parent, qn("mergeCell"), ref=ref)
    merge_parent.attrib["count"] = str(len(merge_parent.findall(qn("mergeCell"))))


def merge_identical_runs(root: ET.Element, shared: list[str], column: str, start_row: int, end_row: int) -> list[str]:
    cells = build_cell_map(root)
    refs: list[str] = []
    run_start = None
    run_value = None
    last_row = None
    for row in range(start_row, end_row + 1):
        ref = f"{column}{row}"
        cell = cells.get(ref)
        value = cell_text(cell, shared) if cell is not None else ""
        if not value:
            if run_start is not None and last_row is not None and last_row > run_start:
                refs.append(f"{column}{run_start}:{column}{last_row}")
            run_start = None
            run_value = None
            last_row = None
            continue
        if run_start is None:
            run_start = row
            run_value = value
            last_row = row
            continue
        if value == run_value:
            last_row = row
            continue
        if last_row is not None and last_row > run_start:
            refs.append(f"{column}{run_start}:{column}{last_row}")
        run_start = row
        run_value = value
        last_row = row
    if run_start is not None and last_row is not None and last_row > run_start:
        refs.append(f"{column}{run_start}:{column}{last_row}")
    for ref in refs:
        add_merge_ref(root, ref)
    return refs


def apply_patch(template: Path, patch_path: Path, output: Path, *, merge_runs: list[dict] | None = None) -> int:
    patch = parse_patch_file(patch_path)
    sheet_updates = patch.get("sheet_updates", {})
    if merge_runs is None:
        merge_runs = patch.get("merge_runs", [])

    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td) / output.name
        shutil.copyfile(template, tmp)
        with zipfile.ZipFile(tmp, "r") as zin:
            entries = {name: zin.read(name) for name in zin.namelist()}

        for sheet_name, updates in sheet_updates.items():
            sheet_path = sheet_name if sheet_name.startswith("xl/") else f"xl/{sheet_name}"
            if sheet_path not in entries:
                raise RuntimeError(f"sheet not found: {sheet_path}")
            root = ET.fromstring(entries[sheet_path])
            cells = build_cell_map(root)
            for ref, text in updates.items():
                if ref not in cells:
                    raise RuntimeError(f"cell not found: {sheet_name} {ref}")
                set_inline_text(cells[ref], str(text))
            entries[sheet_path] = ET.tostring(root, encoding="utf-8", xml_declaration=True)

        if merge_runs:
            for spec in merge_runs:
                sheet_name = spec["sheet"]
                sheet_path = sheet_name if sheet_name.startswith("xl/") else f"xl/{sheet_name}"
                if sheet_path not in entries:
                    raise RuntimeError(f"sheet not found for merge: {sheet_path}")
                root = ET.fromstring(entries[sheet_path])
                with zipfile.ZipFile(tmp, "r") as zin:
                    shared = read_shared_strings(zin)
                if "ref" in spec:
                    add_merge_ref(root, spec["ref"])
                else:
                    refs = merge_identical_runs(
                        root,
                        shared,
                        spec["column"],
                        int(spec["start_row"]),
                        int(spec["end_row"]),
                    )
                    if not refs:
                        pass
                entries[sheet_path] = ET.tostring(root, encoding="utf-8", xml_declaration=True)

        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as zout:
            for name, data in entries.items():
                zout.writestr(name, data)
    return 0


def validate_workbook(path: Path, template: Path | None = None) -> int:
    with zipfile.ZipFile(path) as zf:
        names = workbook_sheet_names(zf)
        if template:
            with zipfile.ZipFile(template) as tz:
                expected = workbook_sheet_names(tz)
            if names != expected:
                raise RuntimeError(f"sheet order mismatch: {names} != {expected}")
        if "xl/workbook.xml" not in zf.namelist():
            raise RuntimeError("missing workbook.xml")
        if not names:
            raise RuntimeError("no worksheets found")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="excel-srs", description="Excel SRS workbook helper")
    sub = p.add_subparsers(dest="cmd", required=True)

    insp = sub.add_parser("inspect", help="Inspect workbook structure")
    insp.add_argument("--template", type=Path)

    apply_p = sub.add_parser("apply", help="Apply cell patch JSON to a template")
    apply_p.add_argument("--template", type=Path)
    apply_p.add_argument("--patch", type=Path, required=True)
    apply_p.add_argument("--output", type=Path, required=True)

    val = sub.add_parser("validate", help="Validate workbook against template sheet order")
    val.add_argument("--workbook", type=Path, required=True)
    val.add_argument("--template", type=Path)

    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    bundle = bundle_paths()
    template = args.template if hasattr(args, "template") and args.template else bundle.default_template

    if args.cmd == "inspect":
        return inspect_workbook(template)

    if args.cmd == "apply":
        args.output.parent.mkdir(parents=True, exist_ok=True)
        return apply_patch(template, args.patch, args.output)

    if args.cmd == "validate":
        return validate_workbook(args.workbook, template)

    raise RuntimeError(f"unknown command: {args.cmd}")


if __name__ == "__main__":
    raise SystemExit(main())

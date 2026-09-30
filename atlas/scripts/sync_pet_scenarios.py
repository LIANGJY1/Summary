#!/usr/bin/env python3
"""Synchronize the Atlas pet scenario resource from the two authoritative sources."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

SUMMARY_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_COMMANDS = SUMMARY_ROOT / "project/yadi/pet/08-调试注入命令清单.md"
DEFAULT_PET_CASES = Path(
    "/home/liang/Project/Reachauto/YaDi/yadea_master/"
    "application/Launcher/src/test/java/com/yadea/launcher/pet/PetScenarios.kt"
)
RESOURCE = SUMMARY_ROOT / "atlas/app/src/main/resources/pet_scenarios.tsv"
LOCALIZED = SUMMARY_ROOT / "atlas/app/src/main/resources/pet_scenario_zh.tsv"

ACTION_CODES = {0, 1, 2, 3, 4, 5, 6, 11, 12, 13, 14, 70, 80, 90, 100}
EVENTS = {
    "switch_on", "switch_off", "ivi_ready", "render_on", "render_off",
    "clicked", "music_on", "music_off", "voice_on", "voice_off",
    "long_idle", "battery_low", "battery_ok", "plug", "unplug",
    "weather_none", "weather_rain", "weather_snow", "play_start", "play_done",
    "timer_idle", "timer_watchdog", "birthday",
}
STAGES = {
    "骨架与开关显隐": "阶段 1 · 骨架与开关",
    "出场与常驻循环": "阶段 2 · 出场与常驻",
    "点击互动": "阶段 3 · 点击",
    "音乐联动": "阶段 4 · 音乐",
    "语音交互": "阶段 5 · 语音",
    "低电量与充电": "阶段 6 · 电量与充电",
    "表驱动机制锁定": "机制锁定",
    "生日、天气、节日与开关组合": "生日、天气与节日",
    "SRS_007 长期未登录": "长期未登录",
}


def fail(message: str) -> None:
    raise ValueError(message)


def unmarkdown(value: str) -> str:
    value = re.sub(r"\*\*(.*?)\*\*", r"\1", value)
    value = re.sub(r"`([^`]+)`", r"\1", value)
    value = re.sub(r"\s+", " ", value)
    return value.strip(" ;；:：")


def valid_token(token: str) -> bool:
    token = token.strip()
    if token == "fresh_on" or token in {"media_play", "media_pause"}:
        return True
    if token.startswith("pet "):
        args = token.split()
        if len(args) == 2:
            return args[1] in EVENTS
        if len(args) == 3 and args[1] == "festival":
            return args[2].isdigit() and 0 <= int(args[2]) <= 5
        if len(args) == 3 and args[1] == "send_failed":
            return args[2].isdigit() and int(args[2]) in ACTION_CODES
        return False
    if token.startswith("ipc "):
        args = token.split()
        return len(args) == 2 and args[1].isdigit() and int(args[1]) in (set(range(6)) | {14})
    return False


def parse_sequence(cell: str, case_no: int) -> tuple[str, str]:
    snippets = re.findall(r"`([^`]+)`", cell)
    candidates = []
    for snippet in snippets:
        tokens = [item.strip() for item in snippet.split(";")]
        if tokens and all(valid_token(item) for item in tokens):
            candidates.append(snippet)
    if len(candidates) != 1:
        fail(f"case {case_no}: expected one executable sequence, found {len(candidates)}")
    sequence = candidates[0]
    tokens = [item.strip() for item in sequence.split(";")]
    if any(not token or not valid_token(token) for token in tokens):
        fail(f"case {case_no}: invalid DSL sequence {sequence!r}")
    limitation = unmarkdown(re.sub(r"`[^`]+`", " ", cell))
    limitation = re.sub(r"(?:近似正式链|近似链|精确命令)\s*[：:]?", "", limitation)
    limitation = limitation.replace("受 阻断", "受阻断")
    limitation = re.sub(r"\s*[；;]\s*", "；", limitation).strip(" ；;")
    return sequence, limitation


def parse_commands(path: Path) -> list[dict[str, str]]:
    if not path.is_file():
        fail(f"missing command list: {path}")
    rows: list[dict[str, str]] = []
    current_stage = ""
    pattern = re.compile(r"^\|\s*(\d+)\s*\|\s*`([^`]+)`\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*$")
    for line in path.read_text(encoding="utf-8").splitlines():
        heading = re.match(r"^###\s+(.+?)(?:（\d+~\d+）)?$", line)
        if heading:
            raw_stage = heading.group(1).strip()
            raw_stage = re.sub(r"^阶段\s*\d+\s*[:：]\s*", "", raw_stage)
            current_stage = STAGES.get(raw_stage, raw_stage)
        match = pattern.match(line)
        if not match:
            continue
        number, name, command_cell, expected_cell = match.groups()
        case_no = int(number)
        sequence, limitation = parse_sequence(command_cell, case_no)
        rows.append({
            "number": number,
            "stage": current_stage,
            "name": name,
            "sequence": sequence,
            "expected": unmarkdown(expected_cell),
            "limitation": limitation,
        })
    if len(rows) != 99 or [int(row["number"]) for row in rows] != list(range(1, 100)):
        fail(f"command list must contain ordered cases 1..99; found {len(rows)} rows")
    if any(not row["stage"] for row in rows):
        fail("one or more command rows have no stage heading")
    return rows


def parse_source_names(path: Path) -> list[str]:
    if not path.is_file():
        fail(f"missing PetScenarios source: {path}")
    source = path.read_text(encoding="utf-8")
    names = re.findall(r"\bCase\(\"([^\"]+)\"", source)
    if len(names) != 99:
        fail(f"PetScenarios must contain 99 cases; found {len(names)}")
    return names


def parse_localized(path: Path) -> dict[int, str]:
    if not path.is_file():
        fail(f"missing localized titles: {path}")
    result: dict[int, str] = {}
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        parts = line.split("\t", 1)
        if len(parts) != 2 or not parts[0].isdigit() or not parts[1].strip():
            fail(f"{path}:{line_no}: expected number<TAB>Chinese title")
        number, title = int(parts[0]), parts[1].strip()
        if number in result:
            fail(f"{path}:{line_no}: duplicate case number {number}")
        result[number] = title
    if set(result) != set(range(1, 100)):
        fail("localized titles must contain exactly cases 1..99")
    return result


def escape(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")


def build_resource(rows: list[dict[str, str]], names: list[str], titles: dict[int, str]) -> str:
    for index, row in enumerate(rows):
        case_no = index + 1
        if row["name"] != names[index]:
            fail(
                f"case {case_no} source name mismatch: command list={row['name']!r}, "
                f"PetScenarios={names[index]!r}"
            )
        if not titles[case_no]:
            fail(f"case {case_no} has empty Chinese title")
    output = ["# number<TAB>stage<TAB>sourceName<TAB>sequence<TAB>expected<TAB>limitation"]
    for row in rows:
        output.append("\t".join(escape(row[field]) for field in (
            "number", "stage", "name", "sequence", "expected", "limitation"
        )))
    return "\n".join(output) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--commands", type=Path, default=DEFAULT_COMMANDS)
    parser.add_argument("--pet-scenarios", type=Path, default=DEFAULT_PET_CASES)
    parser.add_argument("--check", action="store_true", help="compare generated content without writing")
    args = parser.parse_args()
    try:
        rows = parse_commands(args.commands)
        names = parse_source_names(args.pet_scenarios)
        titles = parse_localized(LOCALIZED)
        content = build_resource(rows, names, titles)
    except (OSError, ValueError) as error:
        print(f"pet scenario sync: {error}", file=sys.stderr)
        return 1
    if args.check:
        current = RESOURCE.read_text(encoding="utf-8") if RESOURCE.exists() else None
        if current != content:
            print(f"generated resource is stale: {RESOURCE}", file=sys.stderr)
            return 1
        print(f"scenario source synchronized: {len(rows)} cases")
        return 0
    RESOURCE.parent.mkdir(parents=True, exist_ok=True)
    RESOURCE.write_text(content, encoding="utf-8")
    print(f"wrote {len(rows)} cases to {RESOURCE}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""
AppStoreApp 分屏形态截图工具。

与 capture_appstore_screenshots.py 同目录，import 复用其基础设施。
分屏编排：桌面 → 分屏图标 → 右格选商店 → 导航到目标页 → 拖拽杆切档 → 验证 → 截图。

用法：
    python3 honda27m-appstore-tools/screenshot/capture_appstore_splitscreenshots.py
    python3 honda27m-appstore-tools/screenshot/capture_appstore_splitscreenshots.py --only 001,005
    python3 honda27m-appstore-tools/screenshot/capture_appstore_splitscreenshots.py --category search
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

# 复用现有脚本的基础设施
sys.path.insert(0, str(Path(__file__).resolve().parent))
import capture_appstore_screenshots as cas

PACKAGE_NAME = cas.PACKAGE_NAME
SERVICE_PACKAGE = cas.SERVICE_PACKAGE
ACTIVITY_MAIN = cas.ACTIVITY_MAIN
ACTIVITY_SEARCH = cas.ACTIVITY_SEARCH
ACTIVITY_DEBUG_HELPER = cas.ACTIVITY_DEBUG_HELPER
REMOTE_SCREENSHOT_DIR = cas.REMOTE_SCREENSHOT_DIR

# 分屏坐标（实测校准）
COORDS_PATH = Path(__file__).with_name("split_coordinates.json")
COORDS: Dict = {}
if COORDS_PATH.exists():
    COORDS = json.loads(COORDS_PATH.read_text(encoding="utf-8"))

SPLIT_ICON_TAP = COORDS.get("split_icon", "input tap 1746 1032")
STORE_ICON_TAP = COORDS.get("store_icon_right", "input tap 1350 690")
DRAG_DURATION = COORDS.get("drag_duration_ms", 900)

# 屏占比模式定义（与 SplitScreenTestController / UI图 一致）
MODE_FULL = "SPLIT_FULL"   # 全屏分屏 1782x1008
MODE_32 = "SPLIT_32"       # 2/3屏 1275x1008
MODE_21 = "SPLIT_21"       # 1/2屏 951x1008（初始档）
MODE_31 = "SPLIT_31"       # 1/3屏 627x1008

MODE_WIDTHS = {
    MODE_FULL: 1782,
    MODE_32: 1275,
    MODE_21: 951,
    MODE_31: 627,
}

# 各档位商店窗格左缘 x（1920 屏，右停靠）
MODE_PANE_LEFT = {
    MODE_FULL: 129,
    MODE_32: 645,
    MODE_21: 969,
    MODE_31: 1293,
}

# 「我的应用」页侧栏「设置」入口：跟随窗格左缘（左缘+93, y=470），实测校准。
# 该页有下载进度动画，uiautomator 等不到 idle 无法 dump，只能用坐标。
SETTINGS_ENTRY_OFFSET_X = 93
SETTINGS_ENTRY_Y = 470

# 首页顶栏「我的」tab 坐标（实测校准，各档位窗格几何不同）。
# uiautomator dump 在本机频繁 idle 超时不可用，导航一律用坐标。
MY_TAB_COORDS = {
    MODE_FULL: (1153, 203),
    MODE_32: (1405, 203),
    MODE_21: (1569, 201),
    MODE_31: (1680, 205),
}

# 从初始 SPLIT_21 出发的拖拽序列（draganddrop x1 y1 x2 y2 duration）
DRAG_TABLE: Dict[str, List[List[int]]] = {
    MODE_21: [],  # 无需拖拽
    MODE_32: [[960, 540, 610, 540]],
    MODE_FULL: [[960, 540, 130, 540]],
    MODE_31: [[960, 540, 130, 540], [129, 540, 1290, 540]],  # 经 FULL 中转
}


@dataclass
class SplitScreenshotTask:
    """单个分屏截图任务。"""
    index: str
    filename: str
    category: str
    description: str
    mode: str  # MODE_FULL / MODE_32 / MODE_21 / MODE_31
    nav_steps: List[str] = field(default_factory=list)
    scenario: Optional[str] = None
    wait_seconds: float = 2.0
    dismiss_keyboard: bool = False


def build_tasks() -> List[SplitScreenshotTask]:
    """构造24个分屏截图任务（6页面×4档位）。"""
    tasks = []
    pages = [
        # (category, base_name, nav_steps, scenario, wait, dismiss_kb)
        ("home", "1.1.2 应用商店首页", [], None, 3.0, False),
        ("dialog", "1.2.2 预装组合包更新确认", [
            f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_PRE_APP_UPDATE "
            f"--ei appType 3 -e appName '应用组合包' -e apkSize '320MB' "
            f"-e preAppName '高德地图,QQ音乐' -e preServiceName '语音服务' "
            f"-n {ACTIVITY_DEBUG_HELPER}",
        ], None, 1.5, False),
        ("search", "1.3.1 应用搜索", [
            f"am start -n {ACTIVITY_SEARCH} -e caller screenshot -e keyword 音乐",
        ], "search_default", 2.0, True),
        ("mine", "2.1.2 我的应用列表-全部更新", [], "mine_all_update", 3.0, False),
        ("mine", "2.2.1 设置页", [], None, 3.0, False),
        ("detail", "3.1.1 应用详情-后装-可更新", [
            f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
            f"-e packageName com.netease.cloudmusic -e appName 网易云音乐 "
            f"-e appVersionId 1047 --ei appType 1 --ei buttonState 0 "
            f"-n {ACTIVITY_DEBUG_HELPER}",
        ], None, 2.0, False),
    ]
    modes = [
        (MODE_FULL, "全屏分屏"),
        (MODE_32, "（2／3屏）"),
        (MODE_21, "（1／2屏）"),
        (MODE_31, "（1／3屏）"),
    ]
    idx = 1
    for cat, base_name, nav, scenario, wait, dismiss_kb in pages:
        for mode, mode_suffix in modes:
            suffix = f"_{mode_suffix}" if mode_suffix != "全屏分屏" else ""
            filename = f"{idx:03d}_{base_name}{suffix}.png"
            tasks.append(SplitScreenshotTask(
                index=f"{idx:03d}",
                filename=filename,
                category=cat,
                description=f"{base_name} {mode_suffix}",
                mode=mode,
                nav_steps=list(nav),
                scenario=scenario,
                wait_seconds=wait,
                dismiss_keyboard=dismiss_kb,
            ))
            idx += 1
    return tasks


def enter_split(device: str, task_index: str = "", output_dir: Optional[Path] = None) -> None:
    """从桌面进入分屏：点分屏图标 → 右格选商店（左格保持应用网格，不打开任何应用）。

    商店冷启动（pm clear 后）耗时波动大，固定 sleep 可能拖在未停靠的空 picker 上，
    因此以 logcat 停靠信号（width 951）确认为准；上一个任务残留状态可能让入口
    点击落空，整流程最多重试 3 次，仍失败则截图留证。
    """
    for attempt in range(3):
        # 先 BACK 退出可能残留的页面/桌面组件编辑模式（HOME 退不出该模式）
        cas.run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
        time.sleep(0.8)
        cas.run_adb(["shell", "input", "keyevent", "KEYCODE_HOME"], device=device)
        time.sleep(2.0)
        cas.run_adb(["shell", "logcat", "-c"], device=device, check=False)
        # 点分屏图标
        cas.run_adb(["shell", SPLIT_ICON_TAP], device=device)
        time.sleep(2.0)
        # 右格选商店
        cas.run_adb(["shell", STORE_ICON_TAP], device=device)
        if wait_for_mode(device, MODE_21, timeout=10.0):
            time.sleep(1.0)  # 停靠后留出布局稳定时间
            return
        # 冷启动慢时补点一次再等
        cas.run_adb(["shell", STORE_ICON_TAP], device=device)
        if wait_for_mode(device, MODE_21, timeout=10.0):
            time.sleep(1.0)
            return
        if attempt < 2:
            print(f"(入口未停靠，整流程重试 {attempt + 1}/3) ", end="", flush=True)
    # 三次都失败，截图留证
    if output_dir is not None:
        dbg = output_dir / f"_debug_enter_split_{task_index}.png"
        cas.capture_screen(device, "/sdcard/enter_split_fail.png")
        cas.pull_screenshot(device, "/sdcard/enter_split_fail.png", dbg)
        print(f" WARN: 商店未停靠，失败现场见 {dbg}")


def wait_for_mode(device: str, expected_mode: str, timeout: float = 8.0) -> bool:
    """轮询 logcat SplitScreenClient 确认目标档位。

    注意：档位日志只在模式切换瞬间及页面动画期间产生，页面稳定后不再输出，
    因此轮询期间绝不能 logcat -c（会把拖拽产生的证据清掉导致永远等不到）。
    清空时机统一放在 execute_task 开头。
    """
    expected_w = MODE_WIDTHS[expected_mode]
    deadline = time.time() + timeout
    last_width = None
    while time.time() < deadline:
        result = cas.run_adb(
            ["shell", "logcat", "-d", "-s", "SplitScreenClient"],
            device=device, check=False,
        )
        text = result.stdout or ""
        if f"width is {expected_w}" in text:
            return True
        widths = re.findall(r"width is (\d+)", text)
        if widths:
            last_width = widths[-1]
        time.sleep(0.5)
    print(f"(logcat 最后宽度: {last_width}) ", end="", flush=True)
    return False


def drag_to_mode(device: str, mode: str) -> bool:
    """从初始 SPLIT_21 拖拽到目标档位。

    每段拖拽后按 logcat 验证档位，未达标重试（draganddrop 偶发不生效，
    尤其是停靠确认后立刻首拖时）；多步路径段间清 logcat。
    """
    steps = DRAG_TABLE.get(mode, [])
    for i, coords in enumerate(steps):
        # SPLIT_31 两步路径：第一段目标是中转档 FULL
        target = MODE_FULL if (mode == MODE_31 and i < len(steps) - 1) else mode
        ok = False
        for attempt in range(3):
            cmd = f"input draganddrop {coords[0]} {coords[1]} {coords[2]} {coords[3]} {DRAG_DURATION}"
            cas.run_adb(["shell", cmd], device=device, check=False)
            time.sleep(2.5)
            if wait_for_mode(device, target, timeout=5.0):
                ok = True
                break
            print(f"(拖拽未达 {target}，重试 {attempt + 1}/3) ", end="", flush=True)
        if not ok:
            return False
        cas.run_adb(["shell", "logcat", "-c"], device=device, check=False)
    return True


def navigate_to_page(device: str, task: SplitScreenshotTask) -> None:
    """在分屏内导航到目标页面。"""
    for step in task.nav_steps:
        cas.run_adb(["shell", step], device=device)
        # am start 的页面在分屏容器内启动较慢，等稳再走后续步骤
        # （过短会导致 dismiss 键盘的 BACK 把还在启动的页面整个关掉）
        time.sleep(3.0)

    # 设置页和我的应用：顶栏「我的」tab 与侧栏「设置」均用实测坐标
    if task.category == "mine":
        mx, my = MY_TAB_COORDS[task.mode]
        cas.run_adb(["shell", f"input tap {mx} {my}"], device=device)
        time.sleep(1.5)
        if "设置" in task.description:
            sx = MODE_PANE_LEFT[task.mode] + SETTINGS_ENTRY_OFFSET_X
            cas.run_adb(["shell", f"input tap {sx} {SETTINGS_ENTRY_Y}"], device=device)
            time.sleep(1.5)

    # 收键盘：仅在键盘确实弹出时发 BACK，否则 BACK 会退出目标页面
    if task.dismiss_keyboard:
        for _ in range(4):
            if cas.is_keyboard_shown(device):
                cas.run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device)
                time.sleep(1.0)
            else:
                break


def execute_task(device: str, task: SplitScreenshotTask, output_dir: Path) -> bool:
    """执行单个分屏截图任务。"""
    print(f"[{task.index}] {task.description} ...", end=" ", flush=True)

    suppressed_imes: List[str] = []
    suppressed_default_ime: Optional[str] = None
    try:
        # 1. 清数据 + 设置场景（清 logcat，档位验证只看本任务产生的日志）
        cas.clear_app(device)
        cas.set_mock_scenario(device, task.scenario)
        cas.run_adb(["shell", "logcat", "-c"], device=device, check=False)

        if task.dismiss_keyboard:
            suppressed_imes, suppressed_default_ime = cas.suppress_soft_keyboard(device)

        # 2. 进入分屏
        enter_split(device, task.index, output_dir)

        # 3. 先拖拽到目标档位（在最终几何尺寸下导航，与截图状态一致）
        if not drag_to_mode(device, task.mode):
            print(f"FAIL（未进入 {task.mode}）")
            return False

        # 4. 导航到目标页面
        navigate_to_page(device, task)

        # 搜索页验证：确认 SearchAppActivity 确实在前台（防止 BACK 误退）
        if task.category == "search" and not cas.wait_for_activity_foreground(device, "SearchAppActivity", timeout=6.0):
            print("WARN: SearchAppActivity 不在前台")

        # 5. 等待页面稳定
        cas.wait_for_idle(task.wait_seconds)

        # 6. 截图
        local_path = output_dir / task.category / task.filename
        remote_path = f"{REMOTE_SCREENSHOT_DIR}/{task.index}.png"
        cas.capture_screen(device, remote_path)
        cas.pull_screenshot(device, remote_path, local_path)

        print("OK")
        return True
    except subprocess.CalledProcessError as e:
        print(f"FAIL: {e.stderr or e.stdout}")
        return False
    finally:
        if suppressed_imes:
            cas.restore_soft_keyboard(device, suppressed_imes, suppressed_default_ime)


def main() -> None:
    parser = argparse.ArgumentParser(description="AppStoreApp 分屏截图工具")
    parser.add_argument("--output", "-o", default=None, help="输出根目录")
    parser.add_argument("--variant", "-V", default=None, help="覆盖模式")
    parser.add_argument("--device", "-d", default=None, help="adb 设备 serial")
    parser.add_argument("--only", default=None, help="仅执行指定序号")
    parser.add_argument("--category", default=None, help="仅执行指定分类")
    parser.add_argument("--list-variants", action="store_true", help="列出模式")
    args = parser.parse_args()

    variant = (args.variant or "").strip() or cas.CURRENT_VARIANT

    if args.list_variants:
        print(f"当前: {variant}")
        print("分屏截图使用 capture_appstore_screenshots.py 的 VARIANTS 配置")
        return

    device = cas.ensure_device_connected(args.device)
    print(f"设备: {device}")
    print(f"变体: {variant}")

    output_dir = cas.resolve_output_dir(args.output, variant)
    if args.output is None:
        # 分屏产物与全屏截图分目录存放，避免同序号文件互相覆盖、干扰 compare_report 配对
        output_dir = output_dir.parent / (output_dir.name + "_split")
    output_dir.mkdir(parents=True, exist_ok=True)
    print(f"输出: {output_dir}")

    # 远端截图目录不存在时 screencap 会失败，先建目录
    cas.run_adb(["shell", "mkdir", "-p", REMOTE_SCREENSHOT_DIR], device=device, check=False)

    tasks = build_tasks()
    if args.category:
        tasks = [t for t in tasks if t.category in args.category.split(",")]
    if args.only:
        only_set = set(args.only.split(","))
        tasks = [t for t in tasks if t.index in only_set]

    success = skip = fail = 0
    for task in tasks:
        if execute_task(device, task, output_dir):
            success += 1
        else:
            fail += 1

    print(f"\n--- 分屏截图完成 ---")
    print(f"成功: {success}, 失败: {fail}")
    print(f"输出: {output_dir}")


if __name__ == "__main__":
    main()

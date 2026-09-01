#!/usr/bin/env python3
"""
AppStoreApp 分屏形态截图工具。

与 capture_appstore_screenshots.py 同目录，import 复用其基础设施。
分屏编排：按页面分组，一个周期内清数据 → 进一次分屏 → 导航到目标页 →
依次拖拽切换档位并截图（不再每张图都重进分屏）。

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
from typing import Dict, List, Optional, Tuple

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

# 组内档位执行顺序：停靠即 1/2 档（零拖拽），再依次 2/3 → 全屏 → 1/3，
# 三段拖拽均为单段直达，落点沿用实测校准值
CAPTURE_ORDER = (MODE_21, MODE_32, MODE_FULL, MODE_31)

# 各档位拖拽杆抓握 x（窗格左缘-9，实测校准；y 居中 540）
DRAG_BAR_X = {
    MODE_FULL: 120,
    MODE_32: 636,
    MODE_21: 960,
    MODE_31: 1284,
}
# 拖到目标档位的释放 x（沿用原实测落点；1/2 无原实测值，取 2/3 与 1/3
# 档位区间之间的安全点，落点由 logcat 宽度验证兜底）
DROP_X = {
    MODE_FULL: 130,
    MODE_32: 610,
    MODE_21: 1000,
    MODE_31: 1290,
}


def transition_path(cur: str, target: str) -> List[str]:
    """从当前档位到目标档位的拖拽路径（途经档位序列，不含起点）。

    仅 1/2→1/3 保留原经 FULL 中转的两段路径（原实测路径），其余均为单段直达。
    """
    if cur == target:
        return []
    if (cur, target) == (MODE_21, MODE_31):
        return [MODE_FULL, MODE_31]
    return [target]


@dataclass
class SplitScreenshotTask:
    """单个分屏截图任务。"""
    index: str
    filename: str
    category: str
    description: str
    mode: str  # MODE_FULL / MODE_32 / MODE_21 / MODE_31


@dataclass
class PageGroup:
    """同一页面的截图周期：进一次分屏、导航一次，拖拽截全部档位。"""
    category: str
    base_name: str
    nav_steps: List[str] = field(default_factory=list)
    scenario: Optional[str] = None
    wait_seconds: float = 2.0
    dismiss_keyboard: bool = False
    # am start 类页面（弹窗/搜索/详情）每档截图前重新导航，但策略不同：
    # - 弹窗/详情（nav_clear_top）：helper 是 standard 启动模式，重复 am start
    #   会叠加实例和弹窗，BACK 又会让窗格失去焦点（拖拽全废），必须用
    #   --activity-clear-top 重建单实例（实测单弹窗、焦点保持、拖拽正常）
    # - 搜索（dismiss_before_drag）：先 BACK 关掉搜索页回首页再拖——搜索页
    #   内容会吞掉拖拽手势，且对已运行的搜索页重复 am start 会被重置回热门推荐
    # 坐标导航页（首页/我的/设置）导航一次后靠拖拽保持页面
    nav_per_mode: bool = False
    nav_clear_top: bool = False
    dismiss_before_drag: bool = False
    tasks: List[SplitScreenshotTask] = field(default_factory=list)


def build_groups() -> List[PageGroup]:
    """构造6个页面周期，共24个分屏截图任务（6页面×4档位）。"""
    return [
        PageGroup("home", "1.1.2 应用商店首页", wait_seconds=3.0),
        PageGroup("dialog", "1.2.2 预装组合包更新确认", nav_steps=[
            f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_PRE_APP_UPDATE "
            f"--ei appType 3 -e appName '应用组合包' -e apkSize '320MB' "
            f"-e preAppName '高德地图,QQ音乐' -e preServiceName '语音服务' "
            f"-n {ACTIVITY_DEBUG_HELPER}",
        ], wait_seconds=1.5, nav_per_mode=True, nav_clear_top=True),
        PageGroup("search", "1.3.1 应用搜索", nav_steps=[
            f"am start -n {ACTIVITY_SEARCH} -e caller screenshot",
        ], scenario="search_default", nav_per_mode=True,
           dismiss_before_drag=True),
        PageGroup("mine", "2.1.2 我的应用列表-全部更新",
                  scenario="mine_all_update", wait_seconds=3.0),
        PageGroup("mine", "2.2.1 设置页", wait_seconds=3.0),
        PageGroup("detail", "3.1.1 应用详情-后装-可更新", nav_steps=[
            f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
            f"-e packageName com.netease.cloudmusic -e appName 网易云音乐 "
            f"-e appVersionId 1047 --ei appType 1 --ei buttonState 0 "
            f"-n {ACTIVITY_DEBUG_HELPER}",
        ], nav_per_mode=True, nav_clear_top=True),
    ]


def attach_tasks(groups: List[PageGroup]) -> None:
    """为每个周期生成 4 个档位任务；序号/文件名与旧版逐任务模式完全一致。"""
    idx = 1
    for g in groups:
        g.tasks = []
        for mode, mode_suffix in ((MODE_FULL, "全屏分屏"), (MODE_32, "（2／3屏）"),
                                  (MODE_21, "（1／2屏）"), (MODE_31, "（1／3屏）")):
            suffix = f"_{mode_suffix}" if mode_suffix != "全屏分屏" else ""
            g.tasks.append(SplitScreenshotTask(
                index=f"{idx:03d}",
                filename=f"{idx:03d}_{g.base_name}{suffix}.png",
                category=g.category,
                description=f"{g.base_name} {mode_suffix}",
                mode=mode,
            ))
            idx += 1


def wait_desktop_settled(device: str, timeout: float = 8.0) -> None:
    """等待分屏壳收起、桌面回到前台（以 topResumedActivity 指向 launcher 为准）。

    pm clear 强杀商店后，分屏壳收起有延迟；桌面未就绪时分屏图标点击会被吞
    （实测停靠本身只需约 3 秒，点击被吞才是入口失败的主因），固定 sleep 等
    不准这个收起过程，必须轮询确认。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = cas.run_adb(
            ["shell", "dumpsys activity activities | grep topResumedActivity"],
            device=device, check=False,
        )
        if "launcher" in (result.stdout or "").lower():
            return
        time.sleep(0.5)


def wait_split_picker(device: str, timeout: float = 5.0) -> bool:
    """轮询确认分屏选择器（SystemUI AllAppActivity）真正打开。

    topResumedActivity 回到 launcher 后，launcher 仍可能短暂不处理点击，
    分屏图标点击会被吞（activity 层面无法区分），以 SurfaceFlinger 图层
    出现 AllAppActivity 为准——picker 关闭时图层为 0，打开时非 0，实测无残影。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = cas.run_adb(
            ["shell", "dumpsys SurfaceFlinger --list | grep -c AllAppActivity"],
            device=device, check=False,
        )
        # grep -c 无匹配时输出 "0"，有匹配时输出计数
        if (result.stdout or "").strip() not in ("", "0"):
            return True
        time.sleep(0.5)
    return False


def enter_split(device: str, task_index: str = "", output_dir: Optional[Path] = None) -> bool:
    """从桌面进入分屏：点分屏图标 → 右格选商店（左格保持应用网格，不打开任何应用）。

    入口失败的根因是"点击被吞"，两处各有验证闭环：
    1. pm clear 强杀商店后 launcher 恢复可点击有延迟（topResumedActivity
       先变、触摸后灵），点分屏图标前先等桌面就绪，并以 SurfaceFlinger
       图层确认 picker 真正打开，未打开则补点分屏图标；
    2. 商店冷启动停靠约 3 秒，点击被吞时以 pidof 判断并补点商店图标
       （见 wait_for_mode）。
    BACK 只在第 2/3 轮整流程重试时作为升级恢复手段（桌面小组件页上按 BACK
    会进入组件编辑/选中态，HOME 退不出）。整流程最多重试 3 次，仍失败则
    截图留证。返回是否停靠成功。
    """
    for attempt in range(3):
        if attempt > 0:
            cas.run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
            time.sleep(0.8)
        cas.run_adb(["shell", "input", "keyevent", "KEYCODE_HOME"], device=device, check=False)
        wait_desktop_settled(device)
        cas.run_adb(["shell", "logcat", "-c"], device=device, check=False)
        # 点分屏图标，picker 确认打开后右格才是可点的商店图标
        picker_open = False
        for _ in range(3):
            cas.run_adb(["shell", SPLIT_ICON_TAP], device=device)
            if wait_split_picker(device, timeout=5.0):
                picker_open = True
                break
        if not picker_open:
            print("(分屏选择器未打开) ", end="", flush=True)
            if attempt < 2:
                print(f"(入口未停靠，整流程重试 {attempt + 1}/3) ", end="", flush=True)
            continue
        time.sleep(1.0)  # picker 刚打开，右格网格渲染缓冲
        # 右格选商店，点击被吞时自动补点
        if wait_for_mode(device, MODE_21, timeout=15.0,
                         retap_cmd=STORE_ICON_TAP, retap_interval=2.0):
            time.sleep(1.0)  # 停靠后留出布局稳定时间
            return True
        if attempt < 2:
            print(f"(入口未停靠，整流程重试 {attempt + 1}/3) ", end="", flush=True)
    # 三次都失败，截图留证
    if output_dir is not None:
        dbg = output_dir / f"_debug_enter_split_{task_index}.png"
        cas.capture_screen(device, "/sdcard/enter_split_fail.png")
        cas.pull_screenshot(device, "/sdcard/enter_split_fail.png", dbg)
        print(f"(失败现场见 {dbg}) ", end="", flush=True)
    return False


def wait_for_mode(device: str, expected_mode: str, timeout: float = 8.0,
                  retap_cmd: Optional[str] = None, retap_interval: float = 2.0) -> bool:
    """轮询 logcat SplitScreenClient 确认目标档位。

    注意：档位日志只在模式切换瞬间及页面动画期间产生，页面稳定后不再输出，
    因此轮询期间绝不能 logcat -c（会把拖拽产生的证据清掉导致永远等不到）。
    清空时机统一放在 execute_task 开头。

    retap_cmd 非空时（进分屏场景）：缓冲区里没有停靠信号且商店进程未被拉起
    （pidof 为空，说明点击落在未渲染完的 picker 上被吞）时，每 retap_interval
    秒补点一次入口图标；进程已在则只等停靠日志、绝不补点——实测冷启动拉起
    到停靠只需约 3 秒，此时补点只会重入 picker 干扰启动。
    """
    expected_w = MODE_WIDTHS[expected_mode]
    deadline = time.time() + timeout
    last_width = None
    last_tap = time.time()
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
        if retap_cmd and time.time() - last_tap >= retap_interval:
            pid = cas.run_adb(["shell", "pidof", PACKAGE_NAME], device=device, check=False)
            if not (pid.stdout or "").strip():
                cas.run_adb(["shell", retap_cmd], device=device, check=False)
                last_tap = time.time()
        time.sleep(0.5)
    print(f"(logcat 最后宽度: {last_width}) ", end="", flush=True)
    return False


def drag_to_mode(device: str, cur: str, target: str) -> bool:
    """从当前档位拖拽到目标档位（可含途经档位）。

    每段拖拽后按 logcat 验证档位，未达标重试（draganddrop 偶发不生效，
    尤其是停靠确认后立刻首拖时）；段间清 logcat。
    """
    for seg_target in transition_path(cur, target):
        ok = False
        for attempt in range(3):
            cmd = (f"input draganddrop {DRAG_BAR_X[cur]} 540 "
                   f"{DROP_X[seg_target]} 540 {DRAG_DURATION}")
            cas.run_adb(["shell", cmd], device=device, check=False)
            time.sleep(2.5)
            if wait_for_mode(device, seg_target, timeout=5.0):
                ok = True
                break
            print(f"(拖拽未达 {seg_target}，重试 {attempt + 1}/3) ", end="", flush=True)
        if not ok:
            return False
        cas.run_adb(["shell", "logcat", "-c"], device=device, check=False)
        cur = seg_target
    return True


def navigate_to_page(device: str, group: PageGroup, mode: str) -> None:
    """在分屏内导航到目标页面（mode 决定坐标导航用的档位几何）。"""
    for step in group.nav_steps:
        if group.nav_clear_top and step.startswith("am start"):
            # 重建 helper 单实例：既消掉上一档的弹窗/页面（不叠加），
            # 又避免 BACK 导致窗格失去焦点
            step = step.replace("am start", "am start --activity-clear-top", 1)
        cas.run_adb(["shell", step], device=device)
        # am start 的页面在分屏容器内启动较慢，等稳再走后续步骤
        # （过短会导致 dismiss 键盘的 BACK 把还在启动的页面整个关掉）
        time.sleep(3.0)

    # 设置页和我的应用：顶栏「我的」tab 与侧栏「设置」均用实测坐标
    if group.category == "mine":
        mx, my = MY_TAB_COORDS[mode]
        cas.run_adb(["shell", f"input tap {mx} {my}"], device=device)
        time.sleep(1.5)
        if "设置" in group.base_name:
            sx = MODE_PANE_LEFT[mode] + SETTINGS_ENTRY_OFFSET_X
            cas.run_adb(["shell", f"input tap {sx} {SETTINGS_ENTRY_Y}"], device=device)
            time.sleep(1.5)

    # 收键盘：仅在键盘确实弹出时发 BACK，否则 BACK 会退出目标页面
    if group.dismiss_keyboard:
        for _ in range(4):
            if cas.is_keyboard_shown(device):
                cas.run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device)
                time.sleep(1.0)
            else:
                break

    # 搜索页验证：确认 SearchAppActivity 确实在前台（防止 BACK 误退）
    if group.category == "search" and not cas.wait_for_activity_foreground(device, "SearchAppActivity", timeout=6.0):
        print("WARN: SearchAppActivity 不在前台 ", end="", flush=True)


def execute_group(device: str, group: PageGroup, output_dir: Path) -> Tuple[int, int]:
    """执行一个页面周期：清数据 → 进一次分屏 → 导航 → 逐档拖拽并截图。

    组内档位在同一会话内连续截取，页面状态天然一致；拖拽失败则放弃本周期
    剩余档位（下一个周期会重新清数据进场，自愈）。
    """
    print(f"\n=== 周期[{group.base_name}] 进一次分屏，截 {len(group.tasks)} 个档位 ===", flush=True)
    tasks_by_mode = {t.mode: t for t in group.tasks}
    capture_seq = [m for m in CAPTURE_ORDER if m in tasks_by_mode]
    results: Dict[str, bool] = {}
    suppressed_imes: List[str] = []
    suppressed_default_ime: Optional[str] = None
    try:
        # 1. 清数据 + 设置场景（清 logcat，档位验证只看本周期产生的日志）
        cas.clear_app(device)
        cas.set_mock_scenario(device, group.scenario)
        cas.run_adb(["shell", "logcat", "-c"], device=device, check=False)

        if group.dismiss_keyboard:
            suppressed_imes, suppressed_default_ime = cas.suppress_soft_keyboard(device)

        # 2. 进入分屏（一次）
        if not enter_split(device, task_index=f"{group.tasks[0].index}-{group.tasks[-1].index}",
                           output_dir=output_dir):
            for t in group.tasks:
                print(f"[{t.index}] {t.description} ... FAIL（商店未停靠）")
                results[t.index] = False
            return _tally(group, results)

        # 3. 坐标导航页（首页/我的/设置）在初始 1/2 档导航一次；
        #    am start 类页面（nav_per_mode）每档截图前单独导航
        if not group.nav_per_mode:
            navigate_to_page(device, group, MODE_21)

        # 4. 逐档拖拽 + 截图
        cur = MODE_21
        for mode in capture_seq:
            task = tasks_by_mode[mode]
            if mode != cur:
                if group.dismiss_before_drag:
                    # 先收键盘（若弹出，BACK 会被键盘消费）再关页面，
                    # 回到干净首页再拖：搜索页内容会吞掉拖拽手势，
                    # 且对已运行的搜索页重复 am start 会被重置回热门推荐
                    for _ in range(3):
                        if not cas.is_keyboard_shown(device):
                            break
                        cas.run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
                        time.sleep(1.0)
                    cas.run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
                    time.sleep(1.2)
                if not drag_to_mode(device, cur, mode):
                    print(f"[{task.index}] {task.description} ... FAIL（未进入 {mode}）")
                    results[task.index] = False
                    for m in capture_seq[capture_seq.index(mode) + 1:]:
                        t = tasks_by_mode[m]
                        print(f"[{t.index}] {t.description} ... FAIL（本周期中止）")
                        results[t.index] = False
                    return _tally(group, results)
                cur = mode
                time.sleep(1.8)  # 拖拽后布局重排
            if group.nav_per_mode:
                navigate_to_page(device, group, mode)
                cas.wait_for_idle(group.wait_seconds)
            print(f"[{task.index}] {task.description} ...", end=" ", flush=True)
            local_path = output_dir / task.category / task.filename
            remote_path = f"{REMOTE_SCREENSHOT_DIR}/{task.index}.png"
            cas.capture_screen(device, remote_path)
            cas.pull_screenshot(device, remote_path, local_path)
            print("OK")
            results[task.index] = True
        return _tally(group, results)
    except subprocess.CalledProcessError as e:
        print(f"FAIL: {e.stderr or e.stdout}")
        for t in group.tasks:
            results.setdefault(t.index, False)
        return _tally(group, results)
    finally:
        if suppressed_imes:
            cas.restore_soft_keyboard(device, suppressed_imes, suppressed_default_ime)


def _tally(group: PageGroup, results: Dict[str, bool]) -> Tuple[int, int]:
    ok = sum(1 for t in group.tasks if results.get(t.index))
    return ok, len(group.tasks) - ok


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

    groups = build_groups()
    attach_tasks(groups)
    if args.category:
        cats = set(args.category.split(","))
        for g in groups:
            g.tasks = [t for t in g.tasks if t.category in cats]
    if args.only:
        only_set = set(args.only.split(","))
        for g in groups:
            g.tasks = [t for t in g.tasks if t.index in only_set]
    groups = [g for g in groups if g.tasks]

    success = fail = 0
    for group in groups:
        ok, bad = execute_group(device, group, output_dir)
        success += ok
        fail += bad

    print(f"\n--- 分屏截图完成 ---")
    print(f"成功: {success}, 失败: {fail}")
    print(f"输出: {output_dir}")


if __name__ == "__main__":
    main()

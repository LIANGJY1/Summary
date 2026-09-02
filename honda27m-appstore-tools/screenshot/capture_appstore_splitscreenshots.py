#!/usr/bin/env python3
"""
AppStoreApp 分屏形态截图工具（独立脚本）。

不依赖全屏脚本 capture_appstore_screenshots.py：包名/路径常量、ADB 基础设施、
模式配置均在本文件内自带，改动本文件不会影响全屏脚本，反之亦然。
分屏编排：按页面分组，一个周期内清数据 → 进一次分屏 → 导航到目标页 →
依次拖拽切换档位并截图（不再每张图都重进分屏）。

用法（命令固定不变，只改脚本顶部配置区）：
    1. 编辑「一键模式配置区（分屏）」的 CURRENT_VARIANT，选目标模式；
    2. 编辑「任务选择配置区」的 CURRENT_TASKS，决定本次执行哪些任务：
       "all"=全部；也可填分类名/分类号/任务序号，逗号分隔、可混用，
       如 "mine"（我的应用分类）、"017"（单个任务）。
    3. 固定运行：
       python3 honda27m-appstore-tools/screenshot/capture_appstore_splitscreenshots.py
       运行时先按分类打印任务清单并标记本次执行项，再开始截图。
辅助参数（可选）：--list-variants 查看全部分屏模式；--variant <名称> 本次临时覆盖；
--device serial / --output 目录 含义不变。
分屏产物写入 screenshots/<模式>_split/，与全屏截图分目录存放，互不覆盖。
"""

import argparse
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

PACKAGE_NAME = "com.hynex.appstoreapp"
SERVICE_PACKAGE = "com.hynex.appstoreservice"
SCENARIO_ACTION = "com.hynex.appstoreservice.MOCK_SCENARIO"
ACTIVITY_SEARCH = f"{PACKAGE_NAME}/.feature.search.SearchAppActivity"
ACTIVITY_DEBUG_HELPER = f"{PACKAGE_NAME}/.screenshot.ScreenshotDebugActivity"
REMOTE_SCREENSHOT_DIR = "/sdcard/AppStoreScreenshots"

# ========================= 一键模式配置区（分屏） =========================
# 要截取哪个分屏模式，就把 CURRENT_VARIANT 改成对应 key，命令保持：
#     python3 honda27m-appstore-tools/screenshot/capture_appstore_splitscreenshots.py
# 分屏模式独立维护（与全屏脚本 VARIANTS 无关）；产物固定写入 screenshots/<模式>_split/，
# 未登记在 SPLIT_VARIANTS 的自定义名称同样可用（自动落同名目录并自动建目录）。
CURRENT_VARIANT = "en_dark_split"
# UE 设计稿（UI图）根目录：模式名按 <语言>_<昼夜>_split 约定自动映射到其下
# 「分屏<cn|en>_<D|L>」子目录，实现 实机图目录 ↔ UI图目录 一一对应（见 resolve_ref_dir）；
# ref_dir 字段仅作手动覆盖用。compare_split_report.py 按同一规则取 UI图。
UI_REF_ROOT = Path.home() / "Documents/HC/UI/extracted_images"
SPLIT_VARIANTS = {
    "zh_dark_split": {
        "desc": "中文 · 黑天 · 分屏",
        "ref_dir": None,  # 自动映射 分屏cn_D
    },
    "en_dark_split": {
        "desc": "English · 黑天 · 分屏",
        "ref_dir": None,  # 自动映射 分屏en_D
    },
    "zh_day_split": {
        "desc": "中文 · 白天 · 分屏",
        "ref_dir": None,  # 自动映射 分屏cn_L
    },
    "en_day_split": {
        "desc": "English · 白天 · 分屏",
        "ref_dir": None,  # 自动映射 分屏en_L
    },
}

# ========================= 任务分类配置区（分屏） =========================
# 分屏截图任务分类元数据：key 对应 SplitScreenshotTask.category 字段（即页面周期所属分类），
# value 用于清单展示与选择。与全屏脚本 CATEGORIES 保持同一套 key。
CATEGORIES = {
    "home": "首页 / Recommendation",
    "dialog": "弹窗 / Dialog",
    "search": "搜索 / Search",
    "mine": "我的应用 / Mine",
    "detail": "应用详情 / AppDetail",
    "restriction": "行驶限制 / Restriction",
}

# ========================= 任务选择配置区（分屏） =========================
# 本次执行哪些分屏截图任务；运行时按分类打印任务清单并标记本次执行项，再开始截图。
# 写法（逗号分隔、可混用）：all=全部；分类号（清单中的 [n]，如 2）；
# 分类名（如 mine）；任务序号（如 017，优先于分类号解释，"017" 是任务、"1" 是分类号）。
# 任务按页面周期执行：只选某页面的部分档位时，该页面仍进一次分屏，只截所选档位。
#
# 任务速查（24 项 = 6 页面 × 4 档位，全部启用；每 4 个连续序号为同一页面的四个档位，
# 依次为 全屏分屏 / 2／3屏 / 1／2屏 / 1／3屏；括号内为对应 UI设计稿编号。
# 任务由 build_groups() 的页面周期自动生成，增删页面后以运行时打印的清单为准）：
#   001-004 应用商店首页（1.1.2）<home>
#   005-008 预装组合包更新确认（1.2.2）<dialog>
#   009-012 应用搜索（1.3.1）<search>
#   013-016 我的应用列表-全部更新（2.1.2）<mine>
#   017-020 设置页（2.2.1）<mine>
#   021-024 应用详情-后装-可更新（3.1.1）<detail>
CURRENT_TASKS = "all"


def resolve_output_dir(output_arg: Optional[str], variant: str) -> Path:
    """解析本次运行的本地输出目录，分屏产物固定写入 screenshots/<模式>_split/。

    优先级：显式 --output（作为根目录，追加变体子目录）> 默认
    screenshots/<variant>_split。相对路径相对脚本所在目录解析，不随当前
    工作目录漂移；与全屏截图分目录存放，避免同序号文件互相覆盖、
    干扰 compare_split_report 配对。
    """
    script_dir = Path(__file__).resolve().parent
    if output_arg:
        return Path(output_arg).resolve() / variant
    return script_dir / "screenshots" / f"{variant}_split"


def resolve_ref_dir(variant: str) -> Optional[str]:
    """按模式名约定解析对应的分屏 UI图 子目录，实现与 extracted_images 一一对应。

    映射规则：<语言>_<昼夜>_split → 分屏<cn|en>_<D|L>，
    其中 zh→cn、en→en，dark→D（黑天）、day→L（白天）。
    不符合约定的名称返回 None，由调用方回退手动配置或内置默认。
    """
    parts = variant.split("_")
    if len(parts) != 3 or parts[2] != "split":
        return None
    lang_code = {"zh": "cn", "en": "en"}.get(parts[0])
    theme_code = {"dark": "D", "day": "L"}.get(parts[1])
    if not (lang_code and theme_code):
        return None
    return str(UI_REF_ROOT / f"分屏{lang_code}_{theme_code}")


def get_mode_ref_dir(variant: str) -> Optional[str]:
    """模式的 UI图 目录：SPLIT_VARIANTS 显式 ref_dir 优先，否则按模式名自动映射。"""
    configured = SPLIT_VARIANTS.get(variant, {}).get("ref_dir")
    if isinstance(configured, str) and configured.strip():
        p = Path(configured.strip()).expanduser()
        return str(p if p.is_absolute() else Path(__file__).resolve().parent / p)
    return resolve_ref_dir(variant)


# ========================= ADB 基础设施 =========================

def run_adb(args: List[str], device: Optional[str] = None, check: bool = True, timeout: float = 30) -> subprocess.CompletedProcess:
    """执行 adb 命令，带超时防止永久阻塞。"""
    cmd = ["adb"]
    if device:
        cmd.extend(["-s", device])
    cmd.extend(args)
    try:
        return subprocess.run(cmd, capture_output=True, text=True, check=check, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        # 超时视为失败，返回带错误信息的 CompletedProcess 占位
        raise subprocess.CalledProcessError(returncode=124, cmd=cmd, output=e.stdout or "", stderr=f"adb timeout after {timeout}s: {' '.join(cmd)}")


def ensure_device_connected(device: Optional[str] = None) -> str:
    """检查 adb 连接，返回设备 serial。"""
    result = run_adb(["devices"], device=None, check=True)
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip() and not line.startswith("List")]
    if not lines:
        sys.exit("错误：没有检测到 adb 设备。")

    devices = []
    for line in lines:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            devices.append(parts[0])

    if len(devices) == 0:
        sys.exit("错误：adb 设备未授权或不可用。")

    if device:
        if device not in devices:
            sys.exit(f"错误：指定的设备 {device} 不在已连接列表中。")
        return device

    if len(devices) > 1:
        sys.exit(f"错误：检测到多个 adb 设备 {devices}，请使用 --device 指定。")

    return devices[0]


def wait_for_idle(timeout: float = 3.5) -> None:
    """等待页面稳定。"""
    time.sleep(timeout)


def wait_for_activity_foreground(device: str, activity_short: str, timeout: float = 10.0) -> bool:
    """轮询等待指定 Activity 处于前台，返回是否成功。"""
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = run_adb(
            ["shell", "dumpsys", "activity", "activities"],
            device=device,
            check=False,
        )
        if activity_short in result.stdout:
            return True
        time.sleep(0.5)
    return False


def is_keyboard_shown(device: str) -> bool:
    """通过 dumpsys input_method 判断软键盘是否处于显示状态。

    优先读 mInputShown；缺失时退回 mImeWindowVis（非 0 即可见）。
    注意：ime disable/enable 操作后 mInputShown 可能残留旧值，仅作消去
    路径的判定依据，不用于校验抑制效果（抑制效果以截图为准）。
    """
    result = run_adb(["shell", "dumpsys", "input_method"], device=device, check=False)
    out = result.stdout or ""
    if "mInputShown=true" in out:
        return True
    if "mInputShown=false" in out:
        return False
    match = re.search(r"mImeWindowVis=(\d+)", out)
    return bool(match and match.group(1) != "0") or ("mImeWindowVis" not in out)


def suppress_soft_keyboard(device: str):
    """禁用所有已启用输入法，从源头阻止截图期间键盘弹出。

    返回 (已禁用的输入法 id 列表, 原默认输入法)，供恢复使用。
    实测车机（讯飞输入法）：搜索页键盘在页面启动约 3.5s 后才弹出
    （onResume 的 show(ime()) 在窗口获得焦点后才生效），事后消去存在
    时机竞态且盲发 BACK 会误退页面；禁用式与时序无关，ime enable 可完整恢复。
    """
    default_ime = (run_adb(["shell", "settings", "get", "secure", "default_input_method"],
                           device=device, check=False).stdout or "").strip() or None
    result = run_adb(["shell", "ime", "list", "-s"], device=device, check=False)
    ime_ids = [line.strip() for line in (result.stdout or "").splitlines() if line.strip()]
    for ime_id in ime_ids:
        run_adb(["shell", "ime", "disable", ime_id], device=device, check=False)
    time.sleep(0.3)
    return ime_ids, default_ime


def restore_soft_keyboard(device: str, ime_ids: List[str], default_ime: Optional[str]) -> None:
    """恢复被禁用的输入法，并确保默认输入法不变。"""
    for ime_id in ime_ids:
        run_adb(["shell", "ime", "enable", ime_id], device=device, check=False)
    if default_ime:
        current = (run_adb(["shell", "settings", "get", "secure", "default_input_method"],
                           device=device, check=False).stdout or "").strip()
        if current != default_ime:
            run_adb(["shell", "ime", "set", default_ime], device=device, check=False)


def capture_screen(device: str, remote_path: str) -> None:
    """使用 adb shell screencap 截图并保存到车机路径。"""
    run_adb(["shell", "screencap", "-p", remote_path], device=device)


def pull_screenshot(device: str, remote_path: str, local_path: Path) -> None:
    """将车机截图拉取到本地。"""
    local_path.parent.mkdir(parents=True, exist_ok=True)
    run_adb(["pull", remote_path, str(local_path)], device=device)


def clear_app(device: str) -> None:
    """强制停止 App 和 Service，并清空 Service 数据，避免旧 DB 版本号影响更新状态。"""
    run_adb(["shell", f"am force-stop {PACKAGE_NAME}"], device=device, check=False)
    run_adb(["shell", f"am force-stop {SERVICE_PACKAGE}"], device=device, check=False)
    run_adb(["shell", f"pm clear {SERVICE_PACKAGE}"], device=device, check=False)
    time.sleep(0.5)


def set_mock_scenario(device: str, scenario: Optional[str]) -> None:
    """通过启动无界面 MockScenarioActivity 设置 AppStoreService 的 mock 数据场景。"""
    run_adb(
        [
            "shell", "am", "start", "-a", SCENARIO_ACTION,
            "-e", "scenario", scenario or "default",
            "-n", "com.hynex.appstoreservice/.mock.MockScenarioActivity",
        ],
        device=device,
        check=False,
    )
    # 等待 activity 写入 scenario
    time.sleep(0.5)


# ========================= 分屏坐标与档位定义 =========================

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


# ========================= 任务分类与选择 =========================

def group_tasks_by_category(tasks: List[SplitScreenshotTask]) -> dict:
    """按 category 分组任务，保持任务定义顺序。"""
    grouped: dict = {}
    for t in tasks:
        grouped.setdefault(t.category, []).append(t)
    return grouped


def print_task_menu(tasks: List[SplitScreenshotTask], selected_indices: Optional[set] = None) -> None:
    """按分类打印任务清单：分类带编号 [n]，任务带原始序号。

    description 本身以设计稿编号开头（如 "1.1.2 应用商店首页"），可直接用于识别。
    传入 selected_indices（子集）时逐项标记执行/跳过；全量或 None 时不出标记。
    """
    grouped = group_tasks_by_category(tasks)
    print(f"\n===== 分屏任务清单（共 {len(tasks)} 项 / {len(grouped)} 类）=====")
    for no, (cat, items) in enumerate(grouped.items(), 1):
        print(f"  [{no}] {CATEGORIES.get(cat, cat)} <{cat}>，{len(items)} 项")
        for t in items:
            mark = ""
            if selected_indices is not None:
                mark = "    <-- 本次执行" if t.index in selected_indices else "    (跳过)"
            print(f"        {t.index}  {t.description}{mark}")
    print()


def parse_task_selection(tasks: List[SplitScreenshotTask], raw: str) -> Optional[tuple]:
    """解析任务选择表达式（CURRENT_TASKS），返回 (分类集合, 任务序号集合)。

    - all / 空 → (None, None)，表示全选；
    - 分类号（清单中的 [n]）、分类 key、任务序号（如 017）可混用，逗号分隔；
    - 三位数数字一律按任务序号解释（"017" 是任务，"1" 才是分类号）；
      未命中的三位数说明该任务不存在，直接报错而非当作分类号；
    - 含无法识别的项时返回 None，由调用方报错退出。
    """
    text = raw.strip()
    if text.lower() in ("", "all", "a", "全部"):
        return (None, None)
    cat_keys = list(group_tasks_by_category(tasks).keys())
    all_indices = {t.index for t in tasks}
    categories: set = set()
    indices: set = set()
    for token in text.replace("，", ",").split(","):
        token = token.strip()
        if not token:
            continue
        if token.lower() in ("all", "全部"):
            return (None, None)
        if token in all_indices:
            indices.add(token)
        elif re.fullmatch(r"\d{3,}", token):
            print(f"  ! 任务 {token} 不在当前清单中（分屏任务为 001-024，见上方速查表）")
            return None
        elif token.isdigit() and 1 <= int(token) <= len(cat_keys):
            categories.add(cat_keys[int(token) - 1])
        elif token in cat_keys:
            categories.add(token)
        else:
            print(f"  ! 无法识别: {token}（可用分类: {', '.join(cat_keys)}；任务序号见上方速查表）")
            return None
    if not categories and not indices:
        return (None, None)
    return (categories, indices)


def filter_tasks(tasks: List[SplitScreenshotTask], categories: Optional[set], indices: Optional[set]) -> List[SplitScreenshotTask]:
    """按分类/序号过滤任务，保持定义顺序；两个集合都为空时返回全部。"""
    if not categories and not indices:
        return tasks
    return [
        t for t in tasks
        if (categories and t.category in categories) or (t.index in indices)
    ]


def wait_desktop_settled(device: str, timeout: float = 8.0) -> None:
    """等待分屏壳收起、桌面回到前台（以 topResumedActivity 指向 launcher 为准）。

    pm clear 强杀商店后，分屏壳收起有延迟；桌面未就绪时分屏图标点击会被吞
    （实测停靠本身只需约 3 秒，点击被吞才是入口失败的主因），固定 sleep 等
    不准这个收起过程，必须轮询确认。
    """
    deadline = time.time() + timeout
    while time.time() < deadline:
        result = run_adb(
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
        result = run_adb(
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
            run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
            time.sleep(0.8)
        run_adb(["shell", "input", "keyevent", "KEYCODE_HOME"], device=device, check=False)
        wait_desktop_settled(device)
        run_adb(["shell", "logcat", "-c"], device=device, check=False)
        # 点分屏图标，picker 确认打开后右格才是可点的商店图标
        picker_open = False
        for _ in range(3):
            run_adb(["shell", SPLIT_ICON_TAP], device=device)
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
        capture_screen(device, "/sdcard/enter_split_fail.png")
        pull_screenshot(device, "/sdcard/enter_split_fail.png", dbg)
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
        result = run_adb(
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
            pid = run_adb(["shell", "pidof", PACKAGE_NAME], device=device, check=False)
            if not (pid.stdout or "").strip():
                run_adb(["shell", retap_cmd], device=device, check=False)
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
            run_adb(["shell", cmd], device=device, check=False)
            time.sleep(2.5)
            if wait_for_mode(device, seg_target, timeout=5.0):
                ok = True
                break
            print(f"(拖拽未达 {seg_target}，重试 {attempt + 1}/3) ", end="", flush=True)
        if not ok:
            return False
        run_adb(["shell", "logcat", "-c"], device=device, check=False)
        cur = seg_target
    return True


def navigate_to_page(device: str, group: PageGroup, mode: str) -> None:
    """在分屏内导航到目标页面（mode 决定坐标导航用的档位几何）。"""
    for step in group.nav_steps:
        if group.nav_clear_top and step.startswith("am start"):
            # 重建 helper 单实例：既消掉上一档的弹窗/页面（不叠加），
            # 又避免 BACK 导致窗格失去焦点
            step = step.replace("am start", "am start --activity-clear-top", 1)
        run_adb(["shell", step], device=device)
        # am start 的页面在分屏容器内启动较慢，等稳再走后续步骤
        # （过短会导致 dismiss 键盘的 BACK 把还在启动的页面整个关掉）
        time.sleep(3.0)

    # 设置页和我的应用：顶栏「我的」tab 与侧栏「设置」均用实测坐标
    if group.category == "mine":
        mx, my = MY_TAB_COORDS[mode]
        run_adb(["shell", f"input tap {mx} {my}"], device=device)
        time.sleep(1.5)
        if "设置" in group.base_name:
            sx = MODE_PANE_LEFT[mode] + SETTINGS_ENTRY_OFFSET_X
            run_adb(["shell", f"input tap {sx} {SETTINGS_ENTRY_Y}"], device=device)
            time.sleep(1.5)

    # 收键盘：仅在键盘确实弹出时发 BACK，否则 BACK 会退出目标页面
    if group.dismiss_keyboard:
        for _ in range(4):
            if is_keyboard_shown(device):
                run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device)
                time.sleep(1.0)
            else:
                break

    # 搜索页验证：确认 SearchAppActivity 确实在前台（防止 BACK 误退）
    if group.category == "search" and not wait_for_activity_foreground(device, "SearchAppActivity", timeout=6.0):
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
        clear_app(device)
        set_mock_scenario(device, group.scenario)
        run_adb(["shell", "logcat", "-c"], device=device, check=False)

        if group.dismiss_keyboard:
            suppressed_imes, suppressed_default_ime = suppress_soft_keyboard(device)

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
                        if not is_keyboard_shown(device):
                            break
                        run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
                        time.sleep(1.0)
                    run_adb(["shell", "input", "keyevent", "KEYCODE_BACK"], device=device, check=False)
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
                wait_for_idle(group.wait_seconds)
            print(f"[{task.index}] {task.description} ...", end=" ", flush=True)
            local_path = output_dir / task.category / task.filename
            remote_path = f"{REMOTE_SCREENSHOT_DIR}/{task.index}.png"
            capture_screen(device, remote_path)
            pull_screenshot(device, remote_path, local_path)
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
            restore_soft_keyboard(device, suppressed_imes, suppressed_default_ime)


def _tally(group: PageGroup, results: Dict[str, bool]) -> Tuple[int, int]:
    ok = sum(1 for t in group.tasks if results.get(t.index))
    return ok, len(group.tasks) - ok


def main() -> None:
    parser = argparse.ArgumentParser(description="AppStoreApp 分屏截图工具（独立脚本，模式配置见顶部 CURRENT_VARIANT）")
    parser.add_argument("--output", "-o", default=None, help="输出根目录（实际写入 <根目录>/<variant>/；默认 screenshots/<variant>_split）")
    parser.add_argument("--variant", "-V", default=None,
                        help=f"本次临时覆盖模式；默认取脚本顶部 CURRENT_VARIANT={CURRENT_VARIANT}")
    parser.add_argument("--device", "-d", default=None, help="adb 设备 serial")
    parser.add_argument("--list-variants", action="store_true", help="列出全部分屏模式、说明及当前生效项后退出")
    args = parser.parse_args()

    variant = (args.variant or "").strip() or CURRENT_VARIANT

    if args.list_variants:
        print(f"当前生效: {variant}\n")
        print(f"{'模式':<22}说明\t实机图目录\tUI图目录（一一对应）")
        for name, entry in SPLIT_VARIANTS.items():
            ref = get_mode_ref_dir(name) or "-"
            marker = "   <-- 当前生效" if name == variant else ""
            print(f"{name:<22}{entry.get('desc', '')}\tscreenshots/{name}_split\t{ref}{marker}")
        if variant not in SPLIT_VARIANTS:
            print(f"{variant:<22}(自定义)\tscreenshots/{variant}_split\t{get_mode_ref_dir(variant) or '-'}")
        return

    if variant not in SPLIT_VARIANTS:
        print(f"提示: 模式 {variant} 未登记在脚本顶部 SPLIT_VARIANTS 中，将使用默认目录 screenshots/{variant}_split/。")

    # ---- 按「任务选择配置区」的 CURRENT_TASKS 筛选本次执行范围（不依赖设备） ----
    groups = build_groups()
    attach_tasks(groups)
    tasks = [t for g in groups for t in g.tasks]
    parsed = parse_task_selection(tasks, CURRENT_TASKS)
    if parsed is None:
        valid_cats = ", ".join(group_tasks_by_category(tasks).keys())
        sys.exit(
            f"错误：脚本顶部 CURRENT_TASKS = {CURRENT_TASKS!r} 含无法识别的写法；"
            f"可用分类: {valid_cats}；任务序号见上方速查表。"
        )
    categories, indices = parsed
    selected = filter_tasks(tasks, categories, indices)
    if not selected:
        print("CURRENT_TASKS 未匹配到任何分屏截图任务，退出。")
        return
    if len(selected) < len(tasks):
        print_task_menu(tasks, {t.index for t in selected})
    else:
        print_task_menu(tasks)
    print(f"本次执行 {len(selected)}/{len(tasks)} 项: {', '.join(t.index for t in selected)}")

    device = ensure_device_connected(args.device)
    print(f"设备: {device}")
    print(f"变体: {variant}")

    output_dir = resolve_output_dir(args.output, variant)
    output_dir.mkdir(parents=True, exist_ok=True)
    print(f"输出: {output_dir}")

    # 远端截图目录不存在时 screencap 会失败，先建目录
    run_adb(["shell", "mkdir", "-p", REMOTE_SCREENSHOT_DIR], device=device, check=False)

    groups = build_groups()
    attach_tasks(groups)
    # 应用本次任务选择：仅保留被选中的任务，没有选中任务的页面周期整体跳过
    selected_ids = {t.index for t in selected}
    for g in groups:
        g.tasks = [t for t in g.tasks if t.index in selected_ids]
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

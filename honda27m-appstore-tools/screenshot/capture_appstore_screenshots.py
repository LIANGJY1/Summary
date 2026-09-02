#!/usr/bin/env python3
"""
AppStoreApp 车机 UI 实际效果图一键截取工具。

设计原则：
- 以 ADB / adb shell 为主线，不修改 AppStoreApp 业务代码。
- 先跑通 ADB 直接可达的页面；对需要特定状态/弹窗的页面，后续通过 debug helper 扩展。
- 截图保存到车机 `/sdcard/AppStoreScreenshots/`，脚本自动 `adb pull` 到本地输出目录。

前置条件：
- 车机已连接 adb 并开启调试。
- AppStoreApp 已安装（包名 com.hynex.appstoreapp）。
- 运行环境有 Python 3.8+ 和 adb。

用法（命令固定不变，只改脚本顶部配置区）：
    1. 编辑「一键模式配置区」的 CURRENT_VARIANT，选目标模式；
       该模式的输出路径与车机环境准备命令在同处 VARIANTS 里配置。
    2. 编辑「任务选择配置区」的 CURRENT_TASKS，决定本次执行哪些任务：
       "all"=全部；也可填分类名/分类号/任务序号，逗号分隔、可混用，
       如 "search"（整个搜索分类）、"013"（单个任务）、"search,019"（混用）。
    3. 固定运行：
       python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py
       每次运行会先按分类打印任务清单并标记本次执行项，再开始截图。
辅助参数（可选）：--list-variants 查看全部模式；--variant <名称> 本次临时覆盖；
--launch-only / --device serial 含义不变。
各模式写入各自独立子目录，互不覆盖。
"""

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional

PACKAGE_NAME = "com.hynex.appstoreapp"
SERVICE_PACKAGE = "com.hynex.appstoreservice"
SCENARIO_ACTION = "com.hynex.appstoreservice.MOCK_SCENARIO"
ACTIVITY_MAIN = f"{PACKAGE_NAME}/.home.MainActivity"
ACTIVITY_SEARCH = f"{PACKAGE_NAME}/.feature.search.SearchAppActivity"
ACTIVITY_DETAIL = f"{PACKAGE_NAME}/.feature.detail.AppDetailActivity"
ACTIVITY_DEBUG_HELPER = f"{PACKAGE_NAME}/.screenshot.ScreenshotDebugActivity"
REMOTE_SCREENSHOT_DIR = "/sdcard/AppStoreScreenshots"

# ========================= 一键模式配置区 =========================
# 要截取哪个模式，就把 CURRENT_VARIANT 改成对应 key，命令保持：
#     python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py
# 每个模式独立输出子目录，互不覆盖；目录命名约定 <语言>_<昼夜>_<形态>，
# 未登记在 VARIANTS 的自定义名称同样可用（自动落到 screenshots/<名称>/ 并自动建目录）。
CURRENT_VARIANT = "en_dark_fullscreen"
# UE 设计稿（UI图）根目录：模式名按 <语言>_<昼夜>_<形态> 约定自动映射到其下子目录，
# 实现 实机图目录 ↔ UI图目录 一一对应（见 resolve_ref_dir）；ref_dir 字段仅作手动覆盖用。
UI_REF_ROOT = Path.home() / "Documents/HC/UI/extracted_images"
VARIANTS = {
    # ---- 全屏 ----
    "zh_dark_fullscreen": {
        "desc": "中文 · 黑天 · 全屏（当前基线）",
        "output_dir": "screenshots/zh_dark_fullscreen",
        "ref_dir": None,  # 自动映射 AppStore_全屏cn_D
        "device_setup": [],
    },
    "en_dark_fullscreen": {
        "desc": "English · 黑天 · 全屏",
        "output_dir": "screenshots/en_dark_fullscreen",
        "ref_dir": None,  # 自动映射 AppStore_全屏en_D
        "device_setup": [
            # 车机切英文的环境准备命令，实测校准后取消注释即可
            # "settings put system system_locales en-US",
        ],
    },
    "zh_day_fullscreen": {
        "desc": "中文 · 白天 · 全屏",
        "output_dir": "screenshots/zh_day_fullscreen",
        "ref_dir": None,  # 自动映射 AppStore_全屏cn_L
        "device_setup": [
            # "cmd uimode night no",
        ],
    },
    "en_day_fullscreen": {
        "desc": "English · 白天 · 全屏",
        "output_dir": "screenshots/en_day_fullscreen",
        "ref_dir": None,  # 自动映射 AppStore_全屏en_L
        "device_setup": [
            # "settings put system system_locales en-US",
            # "cmd uimode night no",
        ],
    },
    # ---- 分屏 ----
    "zh_dark_split": {
        "desc": "中文 · 黑天 · 分屏",
        "output_dir": "screenshots/zh_dark_split",
        "ref_dir": None,  # 自动映射 分屏cn_D
        "device_setup": [
            # 车机进入分屏的命令，实测校准后取消注释即可
        ],
    },
    "en_dark_split": {
        "desc": "English · 黑天 · 分屏",
        "output_dir": "screenshots/en_dark_split",
        "ref_dir": None,  # 自动映射 分屏en_D
        "device_setup": [
            # "settings put system system_locales en-US",
        ],
    },
    "zh_day_split": {
        "desc": "中文 · 白天 · 分屏",
        "output_dir": "screenshots/zh_day_split",
        "ref_dir": None,  # 自动映射 分屏cn_L
        "device_setup": [
            # "cmd uimode night no",
        ],
    },
    "en_day_split": {
        "desc": "English · 白天 · 分屏",
        "output_dir": "screenshots/en_day_split",
        "ref_dir": None,  # 自动映射 分屏en_L
        "device_setup": [
            # "settings put system system_locales en-US",
            # "cmd uimode night no",
        ],
    },
}

# ========================= 任务分类配置区 =========================
# 截图任务分类元数据：key 对应 ScreenshotTask.category 字段，value 用于清单展示与交互选择。
# 新增分类时在此登记即可；未登记的分类按原始 key 展示，不影响执行。
CATEGORIES = {
    "home": "首页 / Recommendation",
    "dialog": "弹窗 / Dialog",
    "search": "搜索 / Search",
    "mine": "我的应用 / Mine",
    "detail": "应用详情 / AppDetail",
    "restriction": "行驶限制 / Restriction",
}

# ========================= 任务选择配置区 =========================
# 本次执行哪些截图任务；运行时按分类打印任务清单并标记本次执行项，再开始截图。
# 写法（逗号分隔、可混用）：all=全部；分类号（清单中的 [n]，如 2）；
# 分类名（如 search）；任务序号（如 013，优先于分类号解释，"013" 是任务、"1" 是分类号）。
CURRENT_TASKS = "dialog"
#
# 任务速查（38 项全量；◆=已启用，○=已注释归档——在 build_tasks() 中取消注释即可恢复；
# 括号内为对应 UI设计稿编号。本表为静态参考，运行时清单始终反映当前实际启用项）：
#   首页 <home>
#       002 按钮状态全（1.1.6）◆
#       001 功能入口（1.1.1）○
#       003 首页加载中（1.1.3）○
#       004 首页加载失败（1.1.4）○
#       005 首页空数据（1.1.5）○
#       006 首页 banner 异常（1.1.8）○
#       007 首页无 banner（1.1.9）○
#       008 首页滚动（1.1.10）○
#   弹窗 <dialog>
#       023 删除确认弹窗（2.1.7）◆
#       009 预装单个应用更新确认（1.2.1）○
#       010 预装组合包更新确认（1.2.2）○
#       011 预装应用安装前确认（1.2.3）○
#       012 预装应用更新完成提醒（1.2.4）○
#       025 自动更新弹窗（2.2.3）○
#       026 还原确认弹窗（2.2.4）○
#       027 HCC 弹窗查看（2.2.5）○
#       028 HCC 弹窗 loading（2.2.6）○
#       029 HCC 弹窗加载失败（2.2.7）○
#       036 应用详情-放大预览图（3.1.7）○
#       037 应用详情-放大预览图加载失败（3.1.7）○
#   搜索 <search>
#       013 应用搜索默认（1.3.1）◆
#       014 搜索 loading（1.3.3）◆
#       015 搜索结果（1.3.4）◆
#       016 搜索结果为空（1.3.5）◆
#       017 搜索异常（1.3.6）◆
#       018 搜索热门推荐无网络（1.3.7）◆
#   我的应用 <mine>
#       019 我的应用-全部更新（2.1.2）◆
#       020 我的应用加载中（2.1.3）◆
#       021 我的应用无网络（2.1.4）◆
#       022 我的应用-卸载中（2.1.6）◆
#       024 设置页（2.2.1）○
#   应用详情 <detail>
#       030 应用详情-后装-可更新（3.1.1）○
#       031 应用详情-图片加载失败（3.1.1）○
#       032 应用详情-滚动（3.1.2）○
#       033 应用详情 loading（3.1.3）○
#       034 应用详情-已安装-异常（3.1.4）○
#       035 应用详情-未安装-异常（3.1.5）○
#   行驶限制 <restriction>
#       038 三方应用通用走行限制（4.1.1）○


@dataclass
class ScreenshotTask:
    """单个截图任务。"""
    index: str          # 参考图序号，如 "001"
    filename: str       # 输出文件名（不含路径）
    category: str       # 页面分类
    description: str    # 中文描述
    adb_direct: bool    # 是否 ADB 直接可达
    nav_steps: List[str]  # 导航命令列表（adb shell 内部命令）
    prerequisites: str  # 前置条件说明
    scenario: Optional[str] = None  # mock 数据场景
    wait_seconds: Optional[float] = None  # 截图前稳定等待秒数；None 时按 adb_direct 取默认值
    dismiss_keyboard: bool = False  # 截图前是否需要主动收起软键盘（搜索等输入页面必须为 True）
    burst_seconds: Optional[float] = None  # 连拍秒数：设备端连续截屏后本地按亮度特征挑出瞬态帧（加载态专用）
    wait_activity: Optional[str] = None  # am start 后轮询等待该 Activity 前台再继续（入口抢拍用，替代固定 1.5s）
    am_start_wait: Optional[float] = None  # 目标 Activity 前台后的额外稳定秒数，配合 wait_activity 使用；None 取 1.5
    exit_after_capture: bool = False  # 截图成功后立即 am force-stop 退出应用，不让页面滞留车机前台


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


def burst_capture_pick(device: str, seconds: float = 8.0,
                       content_box: tuple = (340, 220, 1580, 980),
                       lo: int = 500, hi: int = 15000) -> Optional[str]:
    """设备端连续截屏 seconds 秒，本地按内容区高亮像素数挑出"加载动画"帧。

    校准值（1920x1080）：空白≈0，加载转圈+文字≈3000，列表≈40000+。
    命中 [lo, hi] 的第一帧即为加载态；未命中则退回最后一帧。
    返回本地临时文件路径，失败返回 None。
    """
    import shutil
    import tempfile
    from PIL import Image
    import numpy as np

    remote_dir = "/sdcard/burst_shots"
    run_adb(["shell", f"rm -rf {remote_dir}; mkdir -p {remote_dir}"], device=device, check=False)
    deadline = time.time() + seconds
    i = 0
    while time.time() < deadline:
        run_adb(["shell", "screencap", "-p", f"{remote_dir}/f{i:03d}.png"], device=device, check=False)
        i += 1
    tmpdir = tempfile.mkdtemp(prefix="burst_")
    run_adb(["pull", remote_dir, tmpdir], device=device, check=False)

    frames = sorted((Path(tmpdir) / "burst_shots").glob("f*.png"))
    fallback = None
    for f in frames:
        try:
            arr = np.array(Image.open(f).convert("L").crop(content_box))
            bright = int((arr > 90).sum())
        except Exception:
            continue
        if lo <= bright <= hi:
            print(f"picked {f.name}(bright={bright})", end=" ")
            shutil.copyfile(f, str(f) + ".keep")
            return str(f) + ".keep"
        if 0 < bright < 20000:
            fallback = str(f)
    if fallback:
        print(f"fallback {Path(fallback).name}", end=" ")
        shutil.copyfile(fallback, fallback + ".keep")
        return fallback + ".keep"
    if frames:
        shutil.copyfile(frames[-1], str(frames[-1]) + ".keep")
        return str(frames[-1]) + ".keep"
    return None


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


def dismiss_keyboard(device: str, max_attempts: int = 3) -> None:
    """兜底消去（仅在输入法抑制不可用时使用）。

    必须先轮询确认键盘已实际显示再发 BACK：键盘约在页面启动 3.5s 后才弹出，
    过早的 BACK 不被输入法消费，会落到 Activity 上导致页面退出。
    """
    deadline = time.time() + 6.0
    while time.time() < deadline and not is_keyboard_shown(device):
        time.sleep(0.4)
    for _ in range(max_attempts):
        if not is_keyboard_shown(device):
            time.sleep(0.5)
            return
        run_adb(["shell", "input", "keyevent", "4"], device=device, check=False)
        time.sleep(0.8)
    if is_keyboard_shown(device):
        print("WARN: 软键盘可能仍未收起，请检查 dumpsys input_method")


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


def set_airplane_mode(device: str, enabled: bool) -> None:
    """切换飞行模式，模拟无网络环境（比单独关闭 wifi/data 更可靠）。"""
    mode = "enable" if enabled else "disable"
    run_adb(["shell", f"cmd connectivity airplane-mode {mode}"], device=device, check=False)
    time.sleep(1.5)


def reset_network(device: str) -> None:
    """恢复网络连接。"""
    set_airplane_mode(device, False)
    run_adb(["shell", "svc wifi enable"], device=device, check=False)
    run_adb(["shell", "svc data enable"], device=device, check=False)


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


def load_coordinates() -> dict:
    """从 honda27m-appstore-tools/screenshot/coordinates.json 加载坐标配置，便于不同设备/分辨率校准。"""
    default = {
        "tab_mine": "input tap 1100 120",
        "tab_mine_settings": "input tap 120 330",
        # 大行程 + 显式时长，避免短滑被判定为点击/被吞掉导致页面未滚动
        "swipe_home_down": "input swipe 648 750 648 200 400",
        "swipe_detail_down": "input swipe 960 700 960 300",
    }
    config_path = Path(__file__).with_name("coordinates.json")
    if config_path.exists():
        try:
            loaded = json.loads(config_path.read_text(encoding="utf-8"))
            default.update(loaded)
        except Exception as e:
            print(f"Warning: failed to load coordinates.json: {e}")
    return default


def resolve_output_dir(output_arg: Optional[str], variant: str) -> Path:
    """解析本次运行的本地输出目录。

    优先级：显式 --output（作为根目录，追加变体子目录）> VARIANTS[variant].output_dir
    > 默认 screenshots/<variant>。相对路径相对脚本所在目录解析，不随当前工作目录漂移；
    各模式写入各自子目录，互不覆盖。
    """
    script_dir = Path(__file__).resolve().parent
    if output_arg:
        return Path(output_arg).resolve() / variant
    configured = VARIANTS.get(variant, {}).get("output_dir")
    if isinstance(configured, str) and configured.strip():
        p = Path(configured.strip())
        return (p if p.is_absolute() else script_dir / p).resolve()
    return script_dir / "screenshots" / variant


def resolve_ref_dir(variant: str) -> Optional[str]:
    """按模式名约定解析对应的 UI图 子目录，实现与 extracted_images 目录一一对应。

    映射规则：<语言>_<昼夜>_<形态> →
        全屏: AppStore_全屏<cn|en>_<D|L>   分屏: 分屏<cn|en>_<D|L>
    其中 zh→cn、en→en，dark→D（黑天）、day→L（白天）。
    不符合约定的名称返回 None，由调用方回退手动配置或内置默认。
    """
    parts = variant.split("_")
    if len(parts) != 3:
        return None
    lang, theme, form = parts
    lang_code = {"zh": "cn", "en": "en"}.get(lang)
    theme_code = {"dark": "D", "day": "L"}.get(theme)
    prefix = {"fullscreen": "AppStore_全屏", "split": "分屏"}.get(form)
    if not (lang_code and theme_code and prefix):
        return None
    return str(UI_REF_ROOT / f"{prefix}{lang_code}_{theme_code}")


def get_mode_ref_dir(variant: str) -> Optional[str]:
    """模式的 UI图 目录：VARIANTS 显式 ref_dir 优先，否则按模式名自动映射。"""
    configured = VARIANTS.get(variant, {}).get("ref_dir")
    if isinstance(configured, str) and configured.strip():
        p = Path(configured.strip()).expanduser()
        return str(p if p.is_absolute() else Path(__file__).resolve().parent / p)
    return resolve_ref_dir(variant)


def get_device_setup_commands(variant: str) -> List[str]:
    """读取模式的车机环境准备命令列表（切语言、昼夜模式、分屏等），未配置返回空列表。

    命令在任务执行前逐条以 adb shell 运行；直接编辑脚本顶部 VARIANTS 对应项即可按模式配置。
    """
    cmds = VARIANTS.get(variant, {}).get("device_setup")
    if not isinstance(cmds, list):
        return []
    return [c for c in cmds if isinstance(c, str) and c.strip()]


def build_tasks() -> List[ScreenshotTask]:
    """根据导航目录构造截图任务列表。"""
    coords = load_coordinates()
    TAB_MINE = coords.get("tab_mine", "input tap 1100 120")
    TAB_MINE_SETTINGS = coords.get("tab_mine_settings", "input tap 120 330")
    SWIPE_HOME_DOWN = coords.get("swipe_home_down", "input swipe 648 750 648 200 400")
    SWIPE_DETAIL_DOWN = coords.get("swipe_detail_down", "input swipe 960 700 960 300")

    # 弹窗截图前先进入对应页面作为背景（helper 为 translucent，可看到下层 Activity）
    BG_SETTINGS = [
        f"am start -n {ACTIVITY_MAIN}",
        TAB_MINE,
        TAB_MINE_SETTINGS,
    ]
    BG_MINE = [
        f"am start -n {ACTIVITY_MAIN}",
        TAB_MINE,
    ]
    BG_DETAIL = [
        f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
        f"-e packageName com.netease.cloudmusic "
        f"-e appName 网易云音乐 "
        f"-e appVersionId 1047 "
        f"--ei appType 1 "
        f"-n {ACTIVITY_DEBUG_HELPER}",
    ]

    tasks = [
#         # 1. 首页 / Recommendation
#         ScreenshotTask(
#             index="001",
#             filename="001_1.1.1 功能入口.png",
#             category="home",
#             description="功能入口",
#             adb_direct=True,
#             nav_steps=[f"am start -n {ACTIVITY_MAIN}"],
#             prerequisites="App 已安装，默认启动页",
#             # 首页默认页需要等接口返回才有实际内容
#             wait_seconds=4.0,
#         ),
        ScreenshotTask(
            index="002",
            filename="002_1.1.6 应用商店-按钮状态（全）.png",
            category="home",
            description="按钮状态全",
            adb_direct=True,
            nav_steps=[f"am start -n {ACTIVITY_MAIN}"],
            prerequisites="网络正常，有数据",
        ),
        ScreenshotTask(
            index="003",
            filename="003_1.1.3 应用商店首页-加载中.png",
            category="home",
            description="首页加载中",
            adb_direct=False,
            nav_steps=[
                # 启动后立即截图，mock 延迟让 loading 状态保持
                f"am start -n {ACTIVITY_MAIN}",
            ],
            prerequisites="mock 延迟 1.5s 加载",
            scenario="home_loading",
            # loading 状态：趁还在加载立即截图
            wait_seconds=0.8,
        ),
        ScreenshotTask(
            index="004",
            filename="004_1.1.4 应用商店首页-加载失败、接口异常.png",
            category="home",
            description="首页加载失败",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
            ],
            prerequisites="mock 失败响应",
            scenario="home_error",
            wait_seconds=2.0,
        ),
        ScreenshotTask(
            index="005",
            filename="005_1.1.5 应用商店首页-Empty.png",
            category="home",
            description="首页空数据",
            adb_direct=False,
            nav_steps=[f"am start -n {ACTIVITY_MAIN}"],
            prerequisites="mock 空列表",
            scenario="home_empty",
            # 空状态需要等 empty view 渲染
            wait_seconds=4.0,
        ),
        ScreenshotTask(
            index="006",
            filename="006_1.1.8 应用商店-banner获取异常.png",
            category="home",
            description="首页 banner 异常",
            adb_direct=False,
            nav_steps=[f"am start -n {ACTIVITY_MAIN}"],
            prerequisites="mock 第三张 banner 图失效",
            scenario="home_banner_error",
        ),
        ScreenshotTask(
            index="007",
            filename="007_1.1.9 应用商店首页-无banner位.png",
            category="home",
            description="首页无 banner",
            adb_direct=False,
            nav_steps=[f"am start -n {ACTIVITY_MAIN}"],
            prerequisites="mock 无 banner 数据",
            scenario="home_no_banner",
        ),
        ScreenshotTask(
            index="008",
            filename="008_1.1.10 应用商店首页_滚动.png",
            category="home",
            description="首页滚动",
            adb_direct=True,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                # 单次短滑动可能被吞掉导致页面未上滑，连续滑动两次确保明显滚出首屏
                SWIPE_HOME_DOWN,
                SWIPE_HOME_DOWN,
            ],
            prerequisites="网络正常，数据足够多",
        ),

        # 2. 预装应用弹窗
        ScreenshotTask(
            index="009",
            filename="009_1.2.1 预装单个应用更新确认.png",
            category="dialog",
            description="预装单个应用更新确认",
            adb_direct=False,
            nav_steps=BG_MINE + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_PRE_APP_UPDATE "
                f"--ei appType 2 "
                f"-e appName '百度地图' "
                f"-e apkSize '156MB' "
                f"-e preAppName '百度地图汽车版' "
                f"-e preServiceName '百度汽车服务' "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="010",
            filename="010_1.2.2 预装组合包更新确认.png",
            category="dialog",
            description="预装组合包更新确认",
            adb_direct=False,
            nav_steps=BG_MINE + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_PRE_APP_UPDATE "
                f"--ei appType 3 "
                f"-e appName '应用组合包' "
                f"-e apkSize '320MB' "
                f"-e preAppName '高德地图,QQ音乐' "
                f"-e preServiceName '语音服务' "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="011",
            filename="011_1.2.3 预装应用安装前确认.png",
            category="dialog",
            description="预装应用安装前确认",
            adb_direct=False,
            nav_steps=BG_MINE + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_PRE_APP_INSTALL "
                f"-e appName '百度地图' "
                f"-e preAppName '百度地图' "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="012",
            filename="012_1.2.4 预装应用更新完成提醒_Notificationg.png",
            category="dialog",
            description="预装应用更新完成提醒",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_PRE_APP_MESSAGE_POPUP "
                f"--ei updateSuccess 1 "
                f"--ei appType 3 "
                f"-e appName '应用组合包' "
                f"-e preAppName '高德地图,QQ音乐' "
                f"-e preServiceName '语音服务' "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="真机需运行消息中心服务；横幅5s自动消去，须在窗口内截图",
            wait_seconds=1.0,
        ),
#         3. 搜索 / Search（所有搜索截图均不允许带软键盘，统一 dismiss_keyboard=True）
        ScreenshotTask(
            index="013",
            filename="013_1.3.1 应用搜索.png",
            category="search",
            description="应用搜索默认",
            adb_direct=False,
            nav_steps=[f"am start -n {ACTIVITY_SEARCH}"],
            prerequisites="mock 热门推荐数据",
            scenario="search_default",
            dismiss_keyboard=True,
        ),
        ScreenshotTask(
            index="014",
            filename="014_1.3.3 搜索页面（loading）_点击搜索按钮键盘收起.png",
            category="search",
            description="搜索 loading",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_SEARCH} -e caller screenshot -e keyword 音乐",
            ],
            # 搜索页 onCreate 即自动发请求并显示 loading；入口抢拍：
            # 前台后约 0.7s 内截屏，search_loading 延迟 5s 回包兜底窗口尾部
            prerequisites="mock 延迟 5s 加载；进入页面立即抢拍 loading",
            scenario="search_loading",
            dismiss_keyboard=True,
            wait_activity="SearchAppActivity",
            am_start_wait=0.3,
            wait_seconds=2.4,
            exit_after_capture=True,
        ),
        ScreenshotTask(
            index="015",
            filename="015_1.3.4 搜索页面（搜索结果）.png",
            category="search",
            description="搜索结果",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_SEARCH} -e caller screenshot -e keyword 音乐",
            ],
            prerequisites="mock 搜索结果",
            scenario="search_default",
            dismiss_keyboard=True,
        ),
        ScreenshotTask(
            index="016",
            filename="016_1.3.5 搜索页面（搜索结果未空）.png",
            category="search",
            description="搜索结果为空",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_SEARCH} -e caller screenshot -e keyword xyznotexist12345",
            ],
            prerequisites="mock 空结果",
            scenario="search_empty",
            # 等空态视图渲染
            wait_seconds=3.0,
            dismiss_keyboard=True,
        ),
        ScreenshotTask(
            index="017",
            filename="017_1.3.6 搜索页面（搜索异常）.png",
            category="search",
            description="搜索异常",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_SEARCH} -e caller screenshot -e keyword 音乐",
            ],
            prerequisites="mock 搜索失败",
            scenario="search_error",
            # 搜索空/异常：等 empty/failed view 渲染
            wait_seconds=3.0,
            dismiss_keyboard=True,
        ),
        ScreenshotTask(
            index="018",
            filename="018_1.3.7 搜索页面（热门推荐无数据、无网络）.png",
            category="search",
            description="搜索热门推荐无网络",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_SEARCH}",
            ],
            prerequisites="mock 热门推荐失败",
            scenario="search_top_error",
            # 等失败占位图渲染
            wait_seconds=3.0,
            dismiss_keyboard=True,
        ),

        # 4. 我的应用 / Mine
        ScreenshotTask(
            index="019",
            filename="019_2.1.2 我的应用列表-全部更新.png",
            category="mine",
            description="我的应用-全部更新",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                TAB_MINE,
            ],
            prerequisites="mock 可更新应用",
            scenario="mine_all_update",
            # Mine 数据加载较慢，多等一下
            wait_seconds=4.0,
        ),
        ScreenshotTask(
            index="020",
            filename="020_2.1.3 我的应用列表-加载中.png",
            category="mine",
            description="我的应用加载中",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                TAB_MINE,
            ],
            # mine_loading 场景 mock 延迟 8s 回包（VspMockProviderImpl）；加载动画在
            # tab 切换后约 2s 出现，tap+2.0+1.5≈3.5s 截图，落在窗口中段且容忍冷启动抖动
            prerequisites="mine_loading 场景延迟回包时抢拍加载中",
            scenario="mine_loading",
            burst_seconds=8.0,
        ),
        ScreenshotTask(
            index="021",
            filename="021_2.1.4 我的应用列表-无网络、异常数据.png",
            category="mine",
            description="我的应用无网络",
            adb_direct=False,
            nav_steps=[
                "cmd connectivity airplane-mode enable",
                f"am start -n {ACTIVITY_MAIN}",
                TAB_MINE,
            ],
            prerequisites="飞行模式下 mock 返回带描述的已安装列表",
            scenario="mine_no_network",
            # 同步请求失败较快，等列表渲染
            wait_seconds=4.0,
        ),
        ScreenshotTask(
            index="022",
            filename="022_2.1.6 我的应用列表-卸载中.png",
            category="mine",
            description="我的应用-卸载中",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                TAB_MINE,
            ],
            prerequisites="mock 应用处于卸载/下载状态",
            scenario="mine_uninstalling",
        ),
        ScreenshotTask(
            index="023",
            filename="023_2.1.7 我的应用列表-删除确认.png",
            category="dialog",
            description="删除确认弹窗",
            adb_direct=False,
            nav_steps=BG_MINE + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_DELETE "
                f"-e packageName com.hynex.nettestapp "
                f"-e appName 爱奇艺 "
                f"-e appIcon 'file:///android_asset/mock_images/8c4ebbe84c514f37bde6f60438f50a55.png' "
                f"-e piconShow 1 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),

        # 5. 设置 / Settings
        ScreenshotTask(
            index="024",
            filename="024_2.2.1 设置页.png",
            category="mine",
            description="设置页",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                TAB_MINE,
                TAB_MINE_SETTINGS,
            ],
            prerequisites="无",
            # Mine/设置页数据加载较慢，多等一下
            wait_seconds=4.0,
        ),
        ScreenshotTask(
            index="025",
            filename="025_2.2.3 自动更新弹窗.png",
            category="dialog",
            description="自动更新弹窗",
            adb_direct=False,
            nav_steps=BG_SETTINGS + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_AUTO_UPDATE -n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="026",
            filename="026_2.2.4 还原确认.png",
            category="dialog",
            description="还原确认弹窗",
            adb_direct=False,
            nav_steps=BG_SETTINGS + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_RESTORE "
                f"-e previousVersion HCC5.00 "
                f"-e currentApps '应用A,应用B' "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="027",
            filename="027_2.2.5 Honda Connect Core 弹窗查看.png",
            category="dialog",
            description="HCC 弹窗查看",
            adb_direct=False,
            nav_steps=BG_SETTINGS + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_HCC "
                f"-e hccContent 'Honda Connect Core<br>版本信息<br>导航出行方面,它的导航应用超给力。不仅能精准规划路线,实时路况信息还能帮你巧妙避开拥堵路段,节省出行时间。即使在复杂导航出行方面,它的导航应用超给力。不仅能精准规划路线,实时路况信息还能帮你巧妙避开拥堵路段,节省出行时间。即使在复杂导航出行方面,它的导航应用超给力。不仅能精准规划路线,实时路况信息还能帮你巧妙避开拥堵路段,节省出行时间。即使在复杂导航出行方面,它的导航应用超给力。不仅能精准规划路线,实时路况信息还能帮你巧妙避开拥堵路段,节省出行时间。即使在复杂导航出行方面,它的导航应用超给力。不仅能精准规划路线,实时路况信息还能帮你巧妙避开拥堵路段,节省出行时间。即使在复杂导航出行方面,它的导航应用超' "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="028",
            filename="028_2.2.6 Honda Connect Core 弹窗查看（Loading）.png",
            category="dialog",
            description="HCC 弹窗 loading",
            adb_direct=False,
            nav_steps=BG_SETTINGS + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_HCC_LOADING -n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="029",
            filename="029_2.2.7 Honda Connect Core 弹窗查看（加载失败）.png",
            category="dialog",
            description="HCC 弹窗加载失败",
            adb_direct=False,
            nav_steps=BG_SETTINGS + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_HCC_FAILED -n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),

        # 6. 应用详情 / AppDetail
        ScreenshotTask(
            index="030",
            filename="030_3.1.1 应用详情-后装-可更新.png",
            category="detail",
            description="应用详情-后装-可更新",
            adb_direct=False,
            nav_steps=[
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
                f"-e packageName com.netease.cloudmusic "
                f"-e appName 网易云音乐 "
                f"-e appVersionId 1047 "
                f"--ei appType 1 "
                f"--ei buttonState 0 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用，后端有数据则显示成功页",
        ),
        ScreenshotTask(
            index="031",
            filename="031_3.1.1 应用详情-后装-图片加载失败.png",
            category="detail",
            description="应用详情-图片加载失败",
            adb_direct=False,
            nav_steps=[
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
                f"-e packageName com.netease.cloudmusic "
                f"-e appName 网易云音乐 "
                f"-e appVersionId 1047 "
                f"--ei appType 1 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="mock 详情预览图失效",
            scenario="detail_image_error",
        ),
        ScreenshotTask(
            index="032",
            filename="032_3.1.2 应用详情-后装-可更新-下.png",
            category="detail",
            description="应用详情-滚动",
            adb_direct=False,
            nav_steps=[
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
                f"-e packageName com.netease.cloudmusic "
                f"-e appName 网易云音乐 "
                f"-e appVersionId 1047 "
                f"--ei appType 1 "
                f"--ei buttonState 0 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
                # 单次滑动只到页面中部，连续滑动多次确保滚动到底部
                SWIPE_DETAIL_DOWN,
                SWIPE_DETAIL_DOWN,
                SWIPE_DETAIL_DOWN,
            ],
            prerequisites="debug helper 可用，详情页成功加载",
        ),
        ScreenshotTask(
            index="033",
            filename="033_3.1.3 应用详情-loading.png",
            category="detail",
            description="应用详情 loading",
            adb_direct=False,
            nav_steps=[
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
                f"-e packageName com.netease.cloudmusic "
                f"-e appName 网易云音乐 "
                f"-e appVersionId 1047 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            # detail_loading 场景延迟 1.8s 回包，需在窗口内抢拍
            scenario="detail_loading",
            wait_seconds=0.6,
            prerequisites="detail_loading 场景延迟回包时抢拍加载中",
        ),
        ScreenshotTask(
            index="034",
            filename="034_3.1.4 应用详情-已安装-无网络、数据异常.png",
            category="detail",
            description="应用详情-已安装-异常",
            adb_direct=False,
            nav_steps=[
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
                f"-e packageName com.netease.cloudmusic "
                f"-e appName QQ音乐 "
                f"-e appVersionId 1047 "
                f"--ei appType 1 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            # UI图要求全描述字段 --；不走 real+飞行模式：com.android.settings 不在商店 DB，
            # isAppInstalled=false 会落到"未安装"失败页而非已安装详情
            prerequisites="detail_installed_error 场景：详情数据异常全字段 --，已安装态显示 更新/卸载",
            scenario="detail_installed_error",
        ),
        ScreenshotTask(
            index="035",
            filename="035_3.1.5 应用详情-未安装-无网络、数据异常.png",
            category="detail",
            description="应用详情-未安装-异常",
            adb_direct=False,
            nav_steps=[
                "cmd connectivity airplane-mode enable",
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DETAIL "
                f"-e packageName com.hynex.notinstalled.app "
                f"-e appName NotInstalledApp "
                f"--ei appType 1 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="无网络 + real 场景绕过 mock，未安装应用走加载失败页",
            # 失败/异常状态需要等网络超时（约 20s）后才能出现
            wait_seconds=3.0,
            scenario="real",
        ),
        ScreenshotTask(
            index="036",
            filename="036_3.1.7 应用详情 放大查看预览图.png",
            category="dialog",
            description="应用详情-放大预览图",
            adb_direct=False,
            nav_steps=BG_DETAIL + [
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_IMAGE_PREVIEW "
                f"-e imageUrls 'file:///android_asset/screenshot_images/preview_music.png,file:///android_asset/screenshot_images/preview_music.png' "
                f"--ei previewIndex 0 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
        ),
        ScreenshotTask(
            index="037",
            filename="037_3.1.7 应用详情 放大查看预览图（图片加载失败）.png",
            category="dialog",
            description="应用详情-放大预览图加载失败",
            adb_direct=False,
            nav_steps=[
                "cmd connectivity airplane-mode enable",
            ] + BG_DETAIL + [
                # file:// 本地资源不受飞行模式影响，必须用无效 URL 才能让 Glide 走 error 占位
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_DIALOG_IMAGE_PREVIEW "
                f"-e imageUrls 'https://invalid.example.com/404.png' "
                f"--ei previewIndex 0 "
                f"-n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="无网络 + 无效图片 URL，Glide 加载失败显示 error 占位",
        ),

        # 7. 行驶限制页
        ScreenshotTask(
            index="038",
            filename="038_4.1.1 三方应用通用走行限制.png",
            category="restriction",
            description="三方应用通用走行限制",
            adb_direct=False,
            nav_steps=[
                f"am start -n {ACTIVITY_MAIN}",
                f"am start -a {PACKAGE_NAME}.screenshot.SHOW_RESTRICTION -n {ACTIVITY_DEBUG_HELPER}",
            ],
            prerequisites="debug helper 可用",
            # 走行限制 toast 停留时间短，尽快截图
            wait_seconds=0.5,
        ),
    ]
    return tasks


def group_tasks_by_category(tasks: List[ScreenshotTask]) -> dict:
    """按 category 分组任务，保持任务定义顺序。"""
    grouped: dict = {}
    for t in tasks:
        grouped.setdefault(t.category, []).append(t)
    return grouped


def spec_no(task: ScreenshotTask) -> str:
    """从输出文件名提取对应 UI设计稿编号（如 1.3.1），没有则返回空串。"""
    m = re.match(r"^\d+_(\d+(?:\.\d+)*)", task.filename)
    return m.group(1) if m else ""


def print_task_menu(tasks: List[ScreenshotTask], selected_indices: Optional[set] = None) -> None:
    """按分类打印任务清单：分类带编号 [n]，任务带原始序号与设计稿编号。

    传入 selected_indices（子集）时逐项标记执行/跳过；全量或 None 时不出标记。
    """
    grouped = group_tasks_by_category(tasks)
    print(f"\n===== 截图任务清单（共 {len(tasks)} 项 / {len(grouped)} 类）=====")
    for no, (cat, items) in enumerate(grouped.items(), 1):
        print(f"  [{no}] {CATEGORIES.get(cat, cat)} <{cat}>，{len(items)} 项")
        for t in items:
            extra = f"    [scenario: {t.scenario}]" if t.scenario else ""
            mark = ""
            if selected_indices is not None:
                mark = "    <-- 本次执行" if t.index in selected_indices else "    (跳过)"
            sn = spec_no(t)
            label = f"{sn} {t.description}" if sn else t.description
            print(f"        {t.index}  {label}{extra}{mark}")
    print()


def parse_task_selection(tasks: List[ScreenshotTask], raw: str) -> Optional[tuple]:
    """解析任务选择表达式（CURRENT_TASKS），返回 (分类集合, 任务序号集合)。

    - all / 空 → (None, None)，表示全选；
    - 分类号（清单中的 [n]）、分类 key、任务序号（如 013）可混用，逗号分隔；
    - 三位数数字一律按任务序号解释（"013" 是任务，"1" 才是分类号）；
      未命中的三位数说明该任务未启用（注释归档）或不存在，直接报错而非当作分类号；
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
            print(f"  ! 任务 {token} 不在当前清单中（已注释归档或不存在，取消 build_tasks() 中注释后可用）")
            return None
        elif token.isdigit() and 1 <= int(token) <= len(cat_keys):
            categories.add(cat_keys[int(token) - 1])
        elif token in cat_keys:
            categories.add(token)
        else:
            print(f"  ! 无法识别: {token}（可用分类: {', '.join(cat_keys)}；任务序号见上方清单）")
            return None
    if not categories and not indices:
        return (None, None)
    return (categories, indices)


def filter_tasks(tasks: List[ScreenshotTask], categories: Optional[set], indices: Optional[set]) -> List[ScreenshotTask]:
    """按分类/序号过滤任务，保持定义顺序；两个集合都为空时返回全部。"""
    if not categories and not indices:
        return tasks
    return [
        t for t in tasks
        if (categories and t.category in categories) or (t.index in indices)
    ]


def execute_task(device: str, task: ScreenshotTask, output_dir: Path, launch_only: bool = False) -> bool:
    """执行单个截图任务，返回是否成功。launch_only=True 时只拉起页面不截图。"""
    print(f"[{task.index}] {task.description} ...", end=" ", flush=True)

    if not task.nav_steps:
        print("SKIP（导航步骤未实现）")
        return False

    suppressed_imes: List[str] = []
    suppressed_default_ime: Optional[str] = None
    try:
        clear_app(device)
        set_mock_scenario(device, task.scenario)

        if task.dismiss_keyboard:
            # 从源头禁用输入法，截图期间键盘无法弹出（时序无关）
            suppressed_imes, suppressed_default_ime = suppress_soft_keyboard(device)

        for step in task.nav_steps:
            run_adb(["shell", step], device=device)
            if step.startswith("am start"):
                # Activity 启动后立即执行 input 会丢事件，等待其完成绘制
                if ACTIVITY_MAIN in step:
                    # 首页需要等接口返回才有点击目标
                    wait_for_activity_foreground(device, "MainActivity", timeout=10.0)
                    wait_for_idle(3.0)
                elif task.wait_activity:
                    # 入口抢拍：目标页面前台即继续，只留极短稳定期，尽快截到首帧状态（如 loading）
                    wait_for_activity_foreground(device, task.wait_activity, timeout=10.0)
                    wait_for_idle(task.am_start_wait if task.am_start_wait is not None else 1.5)
                else:
                    time.sleep(1.5)
            elif step.startswith("input"):
                # 输入类命令后给系统一点处理时间；Tab/页面切换需要更久
                if "swipe" in step:
                    time.sleep(1.0)
                else:
                    time.sleep(2.0 if "tap" in step else 0.5)
            elif "airplane-mode" in step:
                # 飞行模式状态需要一点时间生效
                time.sleep(1.5)

        if task.dismiss_keyboard and not suppressed_imes:
            # 输入法抑制不可用时退回兜底：等键盘确实弹出后再消去
            dismiss_keyboard(device)

        burst_local = None
        if task.burst_seconds and not launch_only:
            burst_local = burst_capture_pick(device, task.burst_seconds)

        if launch_only:
            print("OK")
            return True

        # 不同页面给不同的稳定等待时间：任务显式声明优先，其余按类型取默认
        if task.wait_seconds is not None:
            wait_for_idle(task.wait_seconds)
        elif not task.adb_direct:
            # debug helper 弹窗需要等其显示
            wait_for_idle(2.5)
        else:
            wait_for_idle(2.0)

        # 车机上使用无空格的临时文件名，避免 adb shell 参数解析问题
        local_path = output_dir / task.category / task.filename
        if burst_local:
            import shutil
            local_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(burst_local, local_path)
        else:
            remote_path = f"{REMOTE_SCREENSHOT_DIR}/{task.index}.png"
            capture_screen(device, remote_path)
            pull_screenshot(device, remote_path, local_path)

        if task.exit_after_capture:
            # 截完立即退出，页面不留前台（下一任务本就会 clear_app，单跑时也不滞留）
            run_adb(["shell", f"am force-stop {PACKAGE_NAME}"], device=device, check=False)

        print("OK")
        return True
    except subprocess.CalledProcessError as e:
        print(f"FAIL: {e.stderr or e.stdout}")
        return False
    finally:
        if suppressed_imes:
            restore_soft_keyboard(device, suppressed_imes, suppressed_default_ime)


def main() -> None:
    parser = argparse.ArgumentParser(description="AppStoreApp 车机 UI 一键截图工具")
    parser.add_argument(
        "--output", "-o", default=None,
        help="可选：本地输出根目录，实际写入 <根目录>/<variant>/；未指定时使用脚本顶部 VARIANTS 中该模式的 output_dir",
    )
    parser.add_argument(
        "--variant", "-V", default=None,
        help=f"本次临时覆盖模式；默认取脚本顶部 CURRENT_VARIANT={CURRENT_VARIANT}",
    )
    parser.add_argument(
        "--list-variants", action="store_true",
        help="列出全部模式、说明、输出路径及当前生效项后退出",
    )
    parser.add_argument("--device", "-d", default=None, help="adb 设备 serial")
    parser.add_argument(
        "--launch-only",
        action="store_true",
        help="仅拉起页面对应的模拟数据页面，不截图",
    )
    args = parser.parse_args()

    variant = (args.variant or "").strip() or CURRENT_VARIANT

    if args.list_variants:
        print(f"当前生效: {variant}\n")
        print(f"{'模式':<22}说明\t实机图目录\tUI图目录（一一对应）")
        for name, entry in VARIANTS.items():
            configured = entry.get("output_dir")
            target = configured if isinstance(configured, str) and configured.strip() else f"(默认 screenshots/{name})"
            ref = get_mode_ref_dir(name) or "-"
            marker = "   <-- 当前生效" if name == variant else ""
            print(f"{name:<22}{entry.get('desc', '')}\t{target}\t{ref}{marker}")
        if variant not in VARIANTS:
            print(f"{variant:<22}(自定义)\t(默认 screenshots/{variant})\t{get_mode_ref_dir(variant) or '-'}")
        return

    if variant not in VARIANTS:
        print(f"提示: 模式 {variant} 未登记在脚本顶部 VARIANTS 中，将使用默认目录 screenshots/{variant}/。")

    # ---- 按「任务选择配置区」的 CURRENT_TASKS 筛选本次执行范围（不依赖设备） ----
    tasks = build_tasks()
    parsed = parse_task_selection(tasks, CURRENT_TASKS)
    if parsed is None:
        valid_cats = ", ".join(group_tasks_by_category(tasks).keys())
        sys.exit(
            f"错误：脚本顶部 CURRENT_TASKS = {CURRENT_TASKS!r} 含无法识别的写法；"
            f"可用分类: {valid_cats}；任务序号见上方清单。"
        )
    categories, indices = parsed
    selected = filter_tasks(tasks, categories, indices)
    if not selected:
        print("CURRENT_TASKS 未匹配到任何已启用的截图任务，退出。")
        return
    if len(selected) < len(tasks):
        print_task_menu(tasks, {t.index for t in selected})
    else:
        print_task_menu(tasks)
    print(f"本次执行 {len(selected)}/{len(tasks)} 项: {', '.join(t.index for t in selected)}")

    device = ensure_device_connected(args.device)
    print(f"使用设备: {device}")
    print(f"截图变体: {variant}")

    launch_only = getattr(args, "launch_only", False)

    if not launch_only:
        # 012 通知截图需要通知权限
        run_adb(
            ["shell", "pm", "grant", PACKAGE_NAME, "android.permission.POST_NOTIFICATIONS"],
            device=device,
            check=False,
        )

    output_dir = resolve_output_dir(args.output, variant)
    output_dir.mkdir(parents=True, exist_ok=True)
    print(f"本地输出: {output_dir}")

    setup_cmds = get_device_setup_commands(variant)
    if setup_cmds:
        print("--- 变体环境准备 ---")
        for cmd in setup_cmds:
            run_adb(["shell", cmd], device=device, check=False)
            time.sleep(0.5)

    if not launch_only:
        # 确保车机截图目录存在
        run_adb(["shell", f"mkdir -p {REMOTE_SCREENSHOT_DIR}"], device=device)

    success_count = 0
    skip_count = 0
    fail_count = 0

    for task in selected:
        if execute_task(device, task, output_dir, launch_only=launch_only):
            success_count += 1
        else:
            if not task.nav_steps:
                skip_count += 1
            else:
                fail_count += 1

    if launch_only:
        print("\n--- 页面拉起完成 ---")
        print(f"成功: {success_count}, 跳过: {skip_count}, 失败: {fail_count}")
    else:
        print("\n--- 截图完成 ---")
        print(f"成功: {success_count}, 跳过: {skip_count}, 失败: {fail_count}")
        print(f"本地输出: {output_dir}")

        # 恢复网络（如果之前被脚本关闭）
#         reset_network(device)


if __name__ == "__main__":
    main()
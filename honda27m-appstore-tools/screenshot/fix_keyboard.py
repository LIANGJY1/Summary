#!/usr/bin/env python3
"""车机输入法（软键盘）诊断与修复工具。

背景：capture_appstore_screenshots.py 的截图任务会临时 `ime disable` 全部输入法、
任务结束后恢复。若脚本在恢复前异常退出（设备断连 / Ctrl+C），输入法将保持禁用，
表现为：点击搜索框键盘弹不出来。本工具用于诊断现场并一键修复。

用法：
    python3 honda27m-appstore-tools/screenshot/fix_keyboard.py            # 诊断并自动修复（默认）
    python3 honda27m-appstore-tools/screenshot/fix_keyboard.py status     # 只查看状态，不做修改
    python3 honda27m-appstore-tools/screenshot/fix_keyboard.py restore    # 只执行修复
    python3 honda27m-appstore-tools/screenshot/fix_keyboard.py test       # 修复后拉起搜索页验证键盘弹出
可选：
    -d/--device <serial>      多设备时指定；不填则要求仅连接一台设备
"""

import argparse
import re
import subprocess
import sys
import time

PREFERRED_DEFAULT_IME = "com.iflytek.inputmethod/.IflytekIME"
SEARCH_ACTIVITY = "com.hynex.appstoreapp/.feature.search.SearchAppActivity"


def adb(device, args, timeout=15):
    cmd = ["adb"]
    if device:
        cmd += ["-s", device]
    cmd += args
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)


def shell(device, args, timeout=15):
    return adb(device, ["shell"] + args, timeout).stdout or ""


def pick_device():
    out = adb(None, ["devices"]).stdout or ""
    serials = [ln.split()[0] for ln in out.splitlines()
               if ln.strip() and not ln.startswith("List") and ln.split()[-1] == "device"]
    return serials


def collect_state(device):
    enabled = [s.strip() for s in shell(device, ["ime", "list", "-s"]).splitlines() if s.strip()]
    all_imes = [s.strip() for s in shell(device, ["ime", "list", "-a", "-s"]).splitlines() if s.strip()]
    default = shell(device, ["settings", "get", "secure", "default_input_method"]).strip()
    shown = "true" in shell(device, ["dumpsys", "input_method"])
    return {"enabled": enabled, "all": all_imes, "default": default, "shown": shown}


def print_state(state):
    print(f"已启用输入法 : {state['enabled'] or '（无 —— 键盘不可用）'}")
    print(f"全部输入法   : {state['all']}")
    print(f"默认输入法   : {state['default'] or '（未设置）'}")
    print(f"当前键盘可见 : {state['shown']}")


def restore(device, state):
    disabled = [ime for ime in state["all"] if ime not in state["enabled"]]
    for ime_id in disabled:
        r = adb(device, ["shell", "ime", "enable", ime_id])
        print(f"ime enable {ime_id}: {r.stdout.strip() or r.stderr.strip()}")

    # 默认输入法为空或指向被禁用项时，重新指定
    if state["default"] not in state["enabled"]:
        target = PREFERRED_DEFAULT_IME if PREFERRED_DEFAULT_IME in state["enabled"] else (
            state["default"] if state["default"] in disabled else
            (state["enabled"][0] if state["enabled"] else None))
        if target:
            r = adb(device, ["shell", "ime", "set", target])
            print(f"ime set {target}: {r.stdout.strip() or r.stderr.strip()}")
        else:
            print("WARN: 没有可用输入法可设为默认")


def test_keyboard(device):
    """拉起搜索页并轮询键盘是否自动弹出（车机讯飞约在页面启动 3.5s 后显示）。"""
    shell(device, ["am", "force-stop", "com.hynex.appstoreapp"])
    time.sleep(1)
    shell(device, ["am", "start", "-n", SEARCH_ACTIVITY])
    deadline = time.time() + 10
    while time.time() < deadline:
        if "mInputShown=true" in shell(device, ["dumpsys", "input_method"]):
            print("键盘已自动弹出 ✅")
            return True
        time.sleep(0.5)
    # 未自动弹出时尝试点击屏幕上部中央的搜索框区域再等一轮
    shell(device, ["input", "tap", "960", "120"])
    deadline = time.time() + 6
    while time.time() < deadline:
        if "mInputShown=true" in shell(device, ["dumpsys", "input_method"]):
            print("点击后键盘弹出 ✅")
            return True
        time.sleep(0.5)
    print("键盘仍未弹出 ❌（请手动点击一次搜索框确认；若仍无，重启设备输入法进程：")
    print("  adb shell am force-stop com.iflytek.inputmethod 后重试）")
    return False


def main():
    parser = argparse.ArgumentParser(description="车机输入法诊断与修复")
    parser.add_argument("action", nargs="?", default="fix",
                        choices=["status", "restore", "fix", "test"], help="默认 fix=诊断+修复")
    parser.add_argument("-d", "--device", default=None, help="adb 设备 serial")
    args = parser.parse_args()

    serials = pick_device()
    if not serials:
        sys.exit("错误：没有在线的 adb 设备")
    device = args.device
    if device is None:
        if len(serials) > 1:
            sys.exit(f"错误：检测到多台设备 {serials}，请用 -d 指定")
        device = serials[0]

    state = collect_state(device)
    print(f"== 设备 {device} 输入法状态 ==")
    print_state(state)

    healthy = bool(state["enabled"]) and bool(state["default"]) and state["default"] in state["enabled"]
    if args.action in ("status",):
        return
    if healthy and args.action != "test":
        print("\n输入法状态健康，无需修复。")
        return
    if args.action in ("fix", "restore"):
        print("\n-- 执行修复 --")
        restore(device, state)
        after = collect_state(device)
        print("-- 修复后 --")
        print_state(after)
        if not after["enabled"]:
            sys.exit("修复失败：仍无启用的输入法")

    if args.action == "test":
        print("\n-- 键盘弹出验证 --")
        test_keyboard(device)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""无线 ADB 远程控制手机进行飞书打卡。

前置条件：
1. 手机已开启开发者选项和无线调试（或已通过 USB 执行过 adb tcpip 5555）
2. 手机和电脑在同一 Wi-Fi 下
3. 已知锁屏 PIN（如为图案锁或无锁屏则留空）
"""

import os
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path


class AdbDevice:
    def __init__(self, ip: str, port: int = 5555, adb_path: str = "adb"):
        self.ip = ip
        self.port = port
        self.adb_path = adb_path
        self.usb_mode = (ip.lower() == "usb")
        self.device_serial = None if self.usb_mode else f"{ip}:{port}"

    def _run(self, args: list, check: bool = True, timeout: int = 30) -> subprocess.CompletedProcess:
        cmd = [self.adb_path]
        if self.device_serial:
            cmd.extend(["-s", self.device_serial])
        cmd.extend(args)
        print(f"> {' '.join(cmd)}")
        return subprocess.run(cmd, capture_output=True, text=True, check=check, timeout=timeout)

    def _get_usb_serial(self) -> str:
        result = subprocess.run([self.adb_path, "devices"], capture_output=True, text=True)
        for line in result.stdout.strip().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device" and ":" not in parts[0]:
                return parts[0]
        return ""

    def connect(self) -> bool:
        if self.usb_mode:
            print("使用 USB 连接模式")
            serial = self._get_usb_serial()
            if not serial:
                print("未找到 USB 连接的设备")
                return False
            self.device_serial = serial
            print(f"已选择 USB 设备: {serial}")
            return True

        print(f"正在连接 {self.device_serial} ...")
        result = subprocess.run(
            [self.adb_path, "connect", self.device_serial],
            capture_output=True, text=True
        )
        print(result.stdout.strip())
        if result.returncode != 0 or "failed" in result.stdout.lower():
            return False
        # 确认设备在线
        devices = subprocess.run([self.adb_path, "devices"], capture_output=True, text=True)
        return self.device_serial in devices.stdout and "offline" not in devices.stdout

    def wake(self):
        print("唤醒屏幕")
        self._run(["shell", "input", "keyevent", "26"])
        time.sleep(0.5)

    def keep_awake(self):
        print("保持屏幕常亮")
        self._run(["shell", "svc", "power", "stayon", "true"], check=False)

    def unlock(self, pin: str = ""):
        if not pin:
            print("未提供 PIN，尝试直接上滑解锁")
            self._run(["shell", "input", "swipe", "540", "1800", "540", "800", "300"])
            time.sleep(1)
            return

        print(f"输入 PIN 解锁")
        # 先上滑调出 PIN 输入界面（多数手机适用）
        self._run(["shell", "input", "swipe", "540", "1800", "540", "800", "300"])
        time.sleep(0.8)
        for digit in pin:
            self._run(["shell", "input", "keyevent", f"KEYCODE_{digit}"])
            time.sleep(0.1)
        self._run(["shell", "input", "keyevent", "66"])  # ENTER
        time.sleep(1.5)

    def launch_feishu(self, package: str = "com.ss.android.lark"):
        print(f"启动飞书 {package}")
        # 强制停止后重新启动，确保从主界面开始
        self._run(["shell", "am", "force-stop", package], check=False)
        time.sleep(0.5)
        self._run(["shell", "monkey", "-p", package, "-c", "android.intent.category.LAUNCHER", "1"])
        time.sleep(2.5)

    def dump_ui(self) -> str:
        result = self._run(["shell", "uiautomator", "dump", "/sdcard/window_dump.xml"])
        if result.returncode != 0:
            return ""
        pull = self._run(["pull", "/sdcard/window_dump.xml", "/tmp/window_dump.xml"], check=False)
        if pull.returncode != 0:
            return ""
        try:
            return Path("/tmp/window_dump.xml").read_text(encoding="utf-8")
        except Exception:
            return ""

    def tap_text(self, text: str, partial: bool = True, retries: int = 5, delay: float = 1.0) -> bool:
        for attempt in range(retries):
            xml = self.dump_ui()
            if not xml:
                time.sleep(delay)
                continue

            try:
                parser = ET.XMLParser(resolve_entities=False)
                root = ET.fromstring(xml, parser=parser)
            except TypeError:
                root = ET.fromstring(xml)
            for node in root.iter("node"):
                node_text = node.attrib.get("text", "")
                content_desc = node.attrib.get("content-desc", "")
                bounds = node.attrib.get("bounds", "")

                match = (text == node_text or text == content_desc)
                if partial and not match:
                    match = text in node_text or text in content_desc

                if match and bounds:
                    x1, y1, x2, y2 = _parse_bounds(bounds)
                    cx, cy = (x1 + x2) // 2, (y1 + y2) // 2
                    print(f"点击 '{text}' 坐标 ({cx}, {cy})")
                    self._run(["shell", "input", "tap", str(cx), str(cy)])
                    return True

            print(f"未找到 '{text}'，重试 {attempt + 1}/{retries}")
            time.sleep(delay)
        return False

    def screenshot(self, path: str):
        print(f"截图保存到 {path}")
        try:
            self._run(["shell", "screencap", "-p", "/sdcard/feishu_checkin.png"])
            self._run(["pull", "/sdcard/feishu_checkin.png", path], check=False)
        except subprocess.CalledProcessError:
            print("截图失败：屏幕可能已关闭或设备未解锁")


def _parse_bounds(bounds: str):
    # [x1,y1][x2,y2]
    bounds = bounds.replace("][", ",").replace("[", "").replace("]", "")
    parts = [int(x) for x in bounds.split(",")]
    return parts[0], parts[1], parts[2], parts[3]


def _find_adb() -> str:
    # 优先使用 Android SDK 中的 adb，它通常比系统 adb 更新且已授权
    candidates = [
        "/home/liang/Android/Sdk/platform-tools/adb",
        os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate

    path = shutil.which("adb")
    if path:
        return path

    candidates = [
        "/usr/bin/adb",
        "/usr/local/bin/adb",
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return "adb"


def main():
    if len(sys.argv) < 2:
        print("用法: python feishu_checkin.py <手机IP|usb> [端口] [PIN]")
        print("示例: python feishu_checkin.py 192.168.1.100 5555 1234")
        print("       python feishu_checkin.py usb 5555 1234")
        sys.exit(1)

    ip = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 5555
    pin = sys.argv[3] if len(sys.argv) > 3 else ""

    adb_path = _find_adb()
    if adb_path == "adb" and not shutil.which("adb"):
        print("错误：未找到 adb，请确认 Android SDK platform-tools 已加入 PATH")
        sys.exit(1)

    print(f"使用 adb: {adb_path}")
    device = AdbDevice(ip, port, adb_path)

    if not device.connect():
        if device.usb_mode:
            print("连接失败，请确认手机已通过 USB 连接并授权调试")
        else:
            print("连接失败，请确认无线 ADB 已开启且手机和电脑在同一网络")
        sys.exit(1)

    device.wake()
    device.unlock(pin)
    device.keep_awake()
    device.launch_feishu()

    steps = ["工作台", "考勤系统", "考勤签到"]
    for step in steps:
        if not device.tap_text(step):
            print(f"无法找到 '{step}'，打卡中断")
            device.screenshot("/tmp/feishu_checkin_failed.png")
            print("已保存失败截图 /tmp/feishu_checkin_failed.png")
            sys.exit(1)
        # 考勤系统可能是小程序/WebView，加载时间较长
        time.sleep(5.0 if step == "考勤系统" else 1.5)

    print("打卡流程已执行完毕")
    device.screenshot("/tmp/feishu_checkin_result.png")
    print("结果截图 /tmp/feishu_checkin_result.png")
    device._run(["shell", "svc", "power", "stayon", "false"], check=False)


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""fix_codex_proxy — Codex/ChatGPT 桌面应用「登录失败 Token exchange failed」一键修复。

场景:桌面启动器(chatgpt.desktop / trae-cn.desktop)的 Exec 里钉死了代理地址
(fixproxy 同步的"手机热点网关:7990"),网络一换就成了死地址;Rust 侧(codex
app-server / 登录服务器)继承该环境变量,所有请求报 "error sending request
for url (https://auth.openai.com/oauth/token)",表现为登录失败、界面转圈;
而 Electron/Chromium 侧跟随 GNOME 系统代理,浏览器 OAuth 页反而正常。

修复:实测各候选代理的出口连通性(google generate_204)→ 取第一个可用者
→ 同步两个启动器 Exec → 若 ChatGPT 在跑且代理过期,带新代理重启应用。
只动启动器这一个通道;GNOME 系统代理归 clash-verge/fixproxy 管,这里只探测不修改。

用法:
    fix_codex_proxy.py            # 检测 + 修复 + 重启应用
    fix_codex_proxy.py --check    # 只检测并打印各候选代理状态,不改任何配置

实测案例(2026-10-20):启动器钉着已换网前的 192.168.100.211:7990,登录报
"Token exchange failed: error sending request";同步为 clash 127.0.0.1:7897
并重启应用后,启动日志零网络错误。
"""

from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

TEST_URL = "https://www.google.com/generate_204"
PROBE_TIMEOUT = 6  # 秒;单候选探测上限(TCP 假活但出口死的地址要等满才能判死)

HOME = Path.home()
DESKTOPS = [
    HOME / ".local/share/applications/chatgpt.desktop",
    HOME / ".local/share/applications/trae-cn.desktop",
]
# Exec 无代理时可整体重写进的环境变量(与 fixproxy 同步 chatgpt.desktop 的形态一致)
PROXY_ENV_KEYS = ["HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY"]
NO_PROXY = "localhost,127.0.0.1,::1,192.168.0.0/16"

APP_ANCHOR = "^/usr/lib/chatgpt/"  # 锚定防误杀:只匹配绝对路径启动的 ChatGPT 进程
RESTART_LOG = Path("/tmp/chatgpt-proxyfix.log")
# 重启后从旧进程继承的会话环境键;其余键同样原样继承,这里只列判断用不到的全部保留
SESSION_ENV_KEYS = [
    "DISPLAY", "XAUTHORITY", "DBUS_SESSION_BUS_ADDRESS", "XDG_RUNTIME_DIR",
    "WAYLAND_DISPLAY", "XDG_SESSION_TYPE", "PATH", "HOME", "USER", "LANG",
    "XDG_CURRENT_DESKTOP", "XDG_SESSION_DESKTOP", "XDG_SESSION_ID",
]

PROXY_RE = re.compile(r"http://[\d.]+:\d+")


def run(cmd: list[str], timeout: int = 15) -> tuple[int, str]:
    """跑命令返回 (exit_code, 合流输出);超时/找不到命令按失败处理不抛出。"""
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        return proc.returncode, (proc.stdout + proc.stderr).strip()
    except (subprocess.TimeoutExpired, FileNotFoundError):
        return 124, ""


def probe(proxy: str) -> bool:
    """真实出口探测:经该代理访问 generate_204,拿到 204 才算可用。"""
    code, out = run(["curl", "-sS", "-m", str(PROBE_TIMEOUT), "-x", proxy,
                     "-o", "/dev/null", "-w", "%{http_code}", TEST_URL], timeout=PROBE_TIMEOUT + 5)
    return code == 0 and out.strip() == "204"


def first_default_gateway() -> str | None:
    _, out = run(["ip", "route", "show", "default"])
    parts = out.split()
    return parts[parts.index("via") + 1] if "via" in parts else None


def gnome_proxy() -> str | None:
    _, mode = run(["gsettings", "get", "org.gnome.system.proxy", "mode"])
    if mode.strip("'\" ") != "manual":
        return None
    _, host = run(["gsettings", "get", "org.gnome.system.proxy.https", "host"])
    _, port = run(["gsettings", "get", "org.gnome.system.proxy.https", "port"])
    host, port = host.strip("'\" "), port.strip()
    return f"http://{host}:{port}" if host and port.isdigit() else None


def desktop_current() -> str | None:
    for f in DESKTOPS:
        if not f.is_file():
            continue
        for line in f.read_text().splitlines():
            if line.startswith("Exec=") and (m := PROXY_RE.search(line)):
                return m.group(0)
    return None


def candidates() -> list[tuple[str, str]]:
    """有序去重的候选代理:(地址, 来源说明)。启动器现值优先——它可用就什么都不用改。"""
    items: list[tuple[str, str]] = []
    if cur := desktop_current():
        items.append((cur, "启动器现值"))
    if g := gnome_proxy():
        items.append((g, "GNOME 系统代理"))
    if gw := first_default_gateway():
        items.append((f"http://{gw}:7990", f"网关 {gw}:7990(手机热点模式)"))
    items.append(("http://127.0.0.1:7897", "clash-verge 默认端口"))
    items.append(("http://127.0.0.1:7890", "clash 经典端口"))
    seen: set[str] = set()
    return [x for x in items if not (x[0] in seen or seen.add(x[0]))]


def app_pids() -> list[int]:
    _, out = run(["pgrep", "-f", APP_ANCHOR])
    return [int(p) for p in out.split() if p.isdigit()]


def codex_pids() -> list[int]:
    """codex app-server(Rust 引擎)的 pid——代理真正被使用的地方。"""
    _, out = run(["pgrep", "-f", "^/usr/lib/chatgpt/resources/codex"])
    return [int(p) for p in out.split() if p.isdigit()]


def read_environ(pid: int) -> dict[str, str]:
    try:
        raw = Path(f"/proc/{pid}/environ").read_bytes().split(b"\0")
    except OSError:
        return {}
    env: dict[str, str] = {}
    for kv in raw:
        if b"=" in kv:
            k, v = kv.split(b"=", 1)
            env[k.decode(errors="replace")] = v.decode(errors="replace")
    return env


def app_proxy(pid: int) -> str | None:
    env = read_environ(pid)
    return env.get("https_proxy") or env.get("HTTPS_PROXY")


def session_env(pids: list[int]) -> dict[str, str]:
    """继承旧进程的会话环境。注意 Electron 主进程的 /proc/environ 被清洗过
    (连 DISPLAY 都没有,代理变量同样读不到),所以要挑还带 DISPLAY 的进程读
    (crashpad/codex 等子进程是完好的);全都读不到就退回当前终端环境。"""
    for pid in pids:
        env = read_environ(pid)
        if "DISPLAY" in env or "DBUS_SESSION_BUS_ADDRESS" in env:
            return env
    return {k: v for k, v in os.environ.items() if k in SESSION_ENV_KEYS}


def patch_desktop(path: Path, new_proxy: str) -> str:
    """把 Exec 行里的代理地址换成 new_proxy;原本没有代理就整体包一层 env 前缀。返回结果描述。"""
    if not path.is_file():
        return "文件不存在,跳过"
    lines = path.read_text().splitlines()
    changed, had_proxy = False, False
    for i, line in enumerate(lines):
        if not line.startswith("Exec="):
            continue
        if PROXY_RE.search(line):
            new_line = PROXY_RE.sub(new_proxy, line)
            had_proxy = True
        else:
            body = line[len("Exec="):]
            prefix = " ".join(f"{k}={new_proxy}" for k in PROXY_ENV_KEYS)
            new_line = f"Exec=env {prefix} NO_PROXY={NO_PROXY} no_proxy={NO_PROXY} {body}"
        if new_line != line:
            lines[i] = new_line
            changed = True
    if not changed:
        return f"已是最新({new_proxy})"
    bak = path.with_suffix(path.suffix + ".bak")
    if not bak.exists():
        shutil.copy2(path, bak)
    path.write_text("\n".join(lines) + "\n")
    old = "代理已更新" if had_proxy else "已写入代理 env 前缀"
    return f"{old}(备份 .bak)"


def main() -> int:
    check_only = "--check" in sys.argv[1:]

    print("── ① 探测候选代理 ──")
    winner: str | None = None
    winner_src = ""
    for proxy, src in candidates():
        ok = probe(proxy)
        print(f"  {'✔' if ok else '✘'} {proxy}  ({src})")
        if ok and winner is None:
            winner, winner_src = proxy, src
    if winner is None:
        print("结论: 失败——没有任何候选代理可用,请先检查网络/开启 clash 或手机热点代理")
        return 1
    print(f"  选定: {winner}  ({winner_src})")

    print("── ② 桌面启动器 ──")
    for f in DESKTOPS:
        if check_only:
            cur = None
            if f.is_file():
                for line in f.read_text().splitlines():
                    if line.startswith("Exec=") and (m := PROXY_RE.search(line)):
                        cur = m.group(0)
                        break
            state = "无代理 env(走继承)" if cur is None else ("✔ 指向可用代理" if cur == winner else f"✘ 指向失效地址 {cur}")
            print(f"  {f.name}: {state}")
        else:
            print(f"  {f.name}: {patch_desktop(f, winner)}")

    pids = app_pids()
    cids = codex_pids()
    # 代理是否已经正确,以 app-server 为准(主进程 env 被 Electron 清洗,读不出真值);
    # app-server 还没起来时无法证明正确(None),按需修复处理
    app_ok = any(app_proxy(p) == winner for p in cids) if cids else None

    print("── ③ ChatGPT 应用 ──")
    if not pids:
        print("  当前未运行,下次从启动器打开即带新代理")
    elif app_ok:
        print("  运行中且代理已是最新,无需重启")
    elif check_only:
        cur = app_proxy(cids[0]) if cids else "(未检测到代理)"
        print(f"  ✘ 运行中但代理不是最新({cur}),需执行修复重启应用")
    else:
        old_env = session_env(pids)
        for k in PROXY_ENV_KEYS:
            old_env[k] = winner
        old_env["NO_PROXY"] = old_env["no_proxy"] = NO_PROXY
        run(["pkill", "-f", APP_ANCHOR])
        for _ in range(30):
            if not app_pids():
                break
            time.sleep(0.5)
        else:  # 15s 还有残留(SIGTERM 被忽略的子进程)就强杀,免得新实例撞单实例锁
            run(["pkill", "-9", "-f", APP_ANCHOR])
            time.sleep(1)
        bin_path = shutil.which("chatgpt", path=old_env.get("PATH")) or "chatgpt"
        with RESTART_LOG.open("w") as log:
            subprocess.Popen([bin_path], env=old_env, stdout=log, stderr=subprocess.STDOUT,
                             start_new_session=True)
        ok = False
        for _ in range(30):  # app-server 在主进程起来后数秒才拉起,轮询到位为止
            time.sleep(1)
            cids = codex_pids()
            if cids and any(app_proxy(p) == winner for p in cids):
                ok = True
                break
        print(f"  {'✔ 已重启并注入新代理' if ok else '✘ 重启后未检测到新代理,看日志 ' + str(RESTART_LOG)}")

    trae = run(["pgrep", "-f", "[t]rae-cn"])[1]
    if trae:
        print("  注: Trae 正在运行,重启 Trae 后才会用上新代理")

    print(f"结论: {'检测完成' if check_only else '修复完成'}——可用代理 {winner};"
          + ("若仍登录失败,重启 ChatGPT 后再试" if check_only else "可回 ChatGPT 重新登录"))
    return 0


if __name__ == "__main__":
    sys.exit(main())

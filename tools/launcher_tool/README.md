# launcher_tool — 打卡与启动工具

> **迁移说明（2026-09-29）**：飞书打卡的日常入口已迁至 **atlas 工具页「飞书打卡」卡片**（Kotlin 原生实现，见 `atlas/app/src/main/kotlin/atlas/core/FeishuCheckin.kt` 与 atlas PRD 变更日志）。本目录已移除打卡的脚本注册（config.json）与调度簿记（settings.json）；`feishu_checkin.py` 保留，作为 `feishu_checkin_bot.py`（Telegram 远程触发）的后端与参考实现；ZCode 命令 `/feishu-checkin` 仍直跑它，与 atlas 互为备份。下文 playbook 描述的 adb 流程坑两版实现同源，均适用。

本目录是自用工具集。本文件记录飞书打卡脚本（`feishu_checkin.py`）的用法、adb 连接通道现状与故障排查 playbook。改动脚本前先读这里的「已知坑」，避免重复踩。

## 文件与入口

- `feishu_checkin.py` — 打卡脚本（USB/无线通用）：唤醒 → PIN 解锁 → 飞书 → 工作台 → 考勤系统 → 考勤签到 → 截图 → 取消常亮并熄屏。**日常入口已迁 atlas 工具页**，此处仅 bot 后端与 `/feishu-checkin` 直跑
- `launcher_tool.py` — GUI/CLI 启动器：`python3 launcher_tool.py --list`（打卡脚本注册已移除）
- `settings.json` — 启动器运行时簿记（打卡参数与调度已于 2026-09-29 清理）
- ZCode 命令 `/feishu-checkin`（`~/.zcode/commands/feishu-checkin.md`）→ 直跑本脚本 USB 模式

atlas 侧直跑等价命令：`python3 feishu_checkin.py <手机IP> 5555 <PIN>`（无线）或 `python3 feishu_checkin.py usb 5555 <PIN>`（USB）。

## adb 连接通道现状（2026-09-29）

| 通道 | 标识 | 手机重启后 |
|---|---|---|
| USB | 序列号 `15564219080011W` | 插线即用 |
| 经典 tcpip 模式 | `IP:5555` | 失效，需插线重开 `adb tcpip 5555` |
| 无线调试（Android 11+ TLS） | `IP:<随机端口>` | 开关自恢复、**配对保留**，仅端口随机变 |

- **PC 与手机已配对**（手机「已配对的设备」里有 `liang@liang`），连无线调试端口无需再走配对码流程。
- 手机 IP 由 DHCP 分配，会漂移（实测 `10.10.0.120` → `10.9.4.102` → `10.9.3.138`），**不可信，用前现取**（atlas 版已把 IP 做成卡片配置）。
- 电脑（10.5.1.x）与手机（10.9.x）**跨网段**：`adb mdns` 自动发现不可用，必须显式拿到 IP+端口。

### 手机重启后的无线重连（无需插线）

1. 手机上读端口：设置 → 开发者选项 → 无线调试 → 「IP 地址和端口」。
   注意：vivo 设置**搜索**「无线调试」无结果，必须走开发者选项列表滚动找（或 `adb shell am start -a com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS` 直达）。
2. `adb connect <IP>:<端口>`。
3. 或不碰手机：对手机 IP 扫描临时端口段 32768-60999 找到 adb 端口再 connect（agent 可代劳）。

现取手机 IP：`adb -s <USB序列号> shell ip -f inet addr show wlan0`（需 USB 短暂在线）。

## 故障排查 playbook（按优先级）

1. **`adb devices` 为空**：先 `lsusb | grep 2d95`——没有 = 物理层没连上（线/口/hub 问题），重试命令无意义；修连接或换通道。
2. **线接触不良（设备几秒就掉线）**：别掐时间跑 USB 全流程。后台轮询抢在线窗口（几秒就够）：现取手机 wlan0 IP → `adb tcpip 5555` → `adb connect IP:5555` → 无线跑完整打卡（2026-09-23、09-28 两次实测有效）。
3. **无线 connect 超时**：多半 IP 漂了（或手机重启丢了 tcpip 模式），按上文重连流程处理。
4. **`uiautomator dump` 被杀（退出码 137）**：考勤系统是 WebView 小程序，加载中取不到界面树，属正常现象。脚本已用 `check=False` + `tap_text` 5 次重试兜底，不要改回 `check=True`。
5. **「签到超时，请重新签到」弹窗**：考勤页面会话过期（常见于脚本半路崩掉、页面停留过久）。在旧页面反复点按钮无效，重跑完整流程（force-stop 飞书重进拿新会话）。
6. **adb 连接刚建立几秒内 `input keyevent` 被拒（exit 1）**：系统未稳定的竞态，等设备稳定在线数秒再发指令。
7. **「USB 已连接」系统弹窗挡界面**：脚本已自动识别并点「取消」。

## 打卡结果确认

结果截图拉到 `/tmp/feishu_checkin_result.png`，**必须用 Read 查看确认「打卡成功」弹窗**才算数；状态栏班次为弹性 8:30-17:30，上班签到最早 06:00，下班签到最晚次日 05:59。定位验证办公 Wi-Fi（睿驰沈阳/武汉办公区3）与 GPS 均可通过。

## 经验教训

- 排查连接问题先分层：物理层（lsusb/总线枚举）→ 授权层（adb devices 状态）→ 传输层（IP/端口），别跳层重试。
- 设备短暂在线的窗口足够完成「切无线」这一步；无线一通即摆脱坏线。
- 配置文件里存的 IP/端口会过期，涉及无线连接一律现取。

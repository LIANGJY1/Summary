# Handoff: ChatGPT 桌面应用内 Codex 登录 403 修复(待用户完成最后一步登录)

日期:2026-09-02 · 机器:Ubuntu 22.04(user: liang,X11,DISPLAY=:1)

## 目标
修复 ChatGPT 桌面应用(`/usr/lib/chatgpt/ChatGPT`,Electron,内嵌 codex 后端)登录 Codex 时的报错:
`Token exchange failed: token endpoint returned status 403 Forbidden: Country, region, or territory not supported`。

## 根因(已实证,勿重查)
- codex 后端进程做 OAuth token 交换时**直连**,出口为沈阳联通 `42.84.233.95`,被 OpenAI 按地区拒绝。
- 登录网页部分正常,因为 Chromium 读 GNOME 系统代理(manual → `http://192.168.84.17:7990`)。
- 代理出口为台北 `213.210.4.104`(ByteVirt),属 OpenAI 支持地区。
- 决定性证据(假授权码 POST `https://auth.openai.com/oauth/token`):
  - 走代理 → `401 token_expired`(地区门已通过,只差真实授权码)
  - 直连 → `403 unsupported_country_region_territory`(即用户所见报错)
- 代理隧道本身正常(google/youtube 走代理 200;api.openai.com 走代理 401 属预期)。但该节点访问 `auth.openai.com`/`chatgpt.com` **曾连续 3 次超时后又自行恢复**(openid-configuration 后来走代理 200)→ 节点不稳定,若登录超时先换节点。

## 网络拓扑要点
- 本机 eth0 `192.168.84.128/24`,网关/代理主机 `192.168.84.17`,HTTP 代理端口 `7990`(仅此端口开放,无 socks/其他常见代理端口)。
- 用户口述为"USB 连手机流量 + 开代理";`.17` 上的代理客户端配置本机不可控。

## 已完成的修改(全部可逆)
1. **`~/.local/share/applications/chatgpt.desktop`**(新建,用户级覆盖系统同名启动项):
   `Exec=env HTTP_PROXY=… HTTPS_PROXY=… http_proxy=… https_proxy=… NO_PROXY=localhost,127.0.0.1,::1 no_proxy=… chatgpt %U`,代理均为 `http://192.168.84.17:7990`。已执行 `update-desktop-database ~/.local/share/applications`。
2. **`~/.bashrc`** 末尾追加了同样一组代理变量,块标记 `>>> CODEX_PROXY >>>` / `<<< CODEX_PROXY <<<`,删除该块即可还原。
3. **重启了 ChatGPT 应用**(kill 全部 `/usr/lib/chatgpt` 进程后 `setsid env … /usr/bin/chatgpt` 拉起,日志 `/tmp/chatgpt-app.log`),并验证 **codex app-server 子进程环境已含全部代理变量**(当时 PID 40433,`ps: app-server`)。注意:Electron 主进程 `/proc/<pid>/environ` 里读不到代理变量(疑似应用内部依据 GNOME 代理设置构造子进程环境),但以子进程实际环境为准。
4. 未改动系统全局代理、未用 sudo 改任何文件(会话中用户提供的 sudo 密码已按规约从本文档隐去;如需 sudo 请向用户索取)。

## 待办(下一步)
1. **用户在已重启的应用窗口点击 "Continue to sign in" 并完成 ChatGPT 账号登录**(交互凭据只有用户能输)。
2. 登录成功判据:生成 `~/.codex/auth.json`(该文件当前不存在);应用不再报错。
3. 若报超时/网络错误:`192.168.84.17` 上代理节点不稳 → 让用户在代理客户端**换节点**(禁用香港节点)后重试。
4. 若报 403 复现:检查 codex 子进程环境 `tr '\0' '\n' < /proc/$(pgrep -f 'resources/codex.*app-server' | head -1)/environ | grep -i proxy`;若无代理变量,说明用户不是经新 .desktop 启动的 → 重新走第 3 步重启应用。
5. 后续若代理地址变更:同步修改 `.desktop` 的 Exec 和 `.bashrc` 的 CODEX_PROXY 块。

## 关键路径/命令速查
- 应用:`/usr/bin/chatgpt` → 符号链接 `/usr/lib/chatgpt/codex-launcher`(sh,`exec ChatGPT "$@"`,env 可透传)
- codex 配置:`~/.codex/config.toml`(桌面版配置,含 mcp_servers 等;无 auth 字段)
- 复现验证:`curl -x http://192.168.84.17:7990 -s https://auth.openai.com/.well-known/openid-configuration` 应 200
- 本次测试残留:`/tmp/token_resp.txt`(代理 401)、`/tmp/token_direct.txt`(直连 403)、`/tmp/oidc.json`、`/tmp/chatgpt-app.log`
- GUI 进程会话环境(重启应用用):`DISPLAY=:1 XAUTHORITY=/run/user/1000/gdm/Xauthority XDG_RUNTIME_DIR=/run/user/1000 DBUS_SESSION_BUS_ADDRESS=unix:path=/run/user/1000/bus`

## 踩过的坑
- `pkill -f '/usr/lib/chatgpt'` 会**匹配到执行它的 shell 自身**把自己杀掉;自查进程请用字符类技巧如 `pgrep -f '/usr/lib/chatgp[t]'`。
- `pgrep … | head … || echo "无"` 不会触发 fallback(管道退出码取 head 的 0);判断无结果要看输出是否为空。
- 代理对 `auth.openai.com` 的连通性有波动,单次超时不能下"节点不可用"结论,需重试多次。

## Suggested skills(下一会话按需调用)
- `systematic-debugging` 或 `diagnosing-bugs`:登录仍失败、出现新报错时,先走诊断闭环再动手。
- `verification-before-completion`:宣布"登录修复完成"前,先跑第 2 节的判据命令取证。
- `handoff`:若本会话再次中断,重新生成交接文档。

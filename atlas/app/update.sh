#!/usr/bin/env bash
# Atlas 一键更新：编译 → 测试 → 打包 deb → 安装到本机
# 用法：./update.sh（任意目录下运行均可）
# sudo 密码来源（按优先级）：环境变量 ATLAS_SUDO_PASS > ~/.atlas-sudo-pass > 交互式输入
# 密码不写入本脚本，避免随仓库泄露；一次性配置：echo '你的密码' > ~/.atlas-sudo-pass && chmod 600 ~/.atlas-sudo-pass
set -euo pipefail

cd "$(dirname "$0")"

die() {
  echo "✗ $*" >&2
  exit 1
}

SUDO_PASS="${ATLAS_SUDO_PASS:-}"
if [[ -z "$SUDO_PASS" && -f "$HOME/.atlas-sudo-pass" ]]; then
  SUDO_PASS="$(cat "$HOME/.atlas-sudo-pass")"
fi
sudo_run() {
  if [[ -n "$SUDO_PASS" ]]; then
    printf '%s\n' "$SUDO_PASS" | sudo -S -p '' "$@"
  else
    sudo "$@"
  fi
}

atlas_pids() {
  {
    pgrep -x atlas || true
    pgrep -f '^/opt/atlas/(bin/atlas|lib/app/atlas)( |$)' || true
  } | sort -nu
}

stop_running_atlas() {
  local pids remaining attempt
  pids="$(atlas_pids)"
  if [[ -z "$pids" ]]; then
    echo "==> 没有正在运行的 Atlas 界面"
    return 0
  fi

  echo "==> 关闭已运行的 Atlas 界面：$pids"
  # 先让窗口正常退出，避免留下未写回的配置；超时后再强制结束。
  kill -TERM $pids 2>/dev/null || true
  for attempt in {1..5}; do
    sleep 1
    remaining="$(atlas_pids)"
    [[ -z "$remaining" ]] && return 0
  done

  echo "! Atlas 界面未正常退出，强制结束：$remaining"
  kill -KILL $remaining 2>/dev/null || true
  for attempt in {1..5}; do
    remaining="$(atlas_pids)"
    [[ -z "$remaining" ]] && return 0
    sleep 1
  done
  die "无法关闭已运行的 Atlas 界面：$remaining"
}

launch_atlas() {
  local launcher="/opt/atlas/bin/atlas"
  local log_file="${TMPDIR:-/tmp}/atlas-update-launch.log"
  [[ -x "$launcher" ]] || die "Atlas 已安装，但启动文件不存在：$launcher"

  echo "==> 启动新版本 Atlas"
  nohup "$launcher" >"$log_file" 2>&1 </dev/null &
  local launch_pid=$!
  disown "$launch_pid" 2>/dev/null || true
  sleep 1
  if ! kill -0 "$launch_pid" 2>/dev/null; then
    die "Atlas 新版本启动失败，请查看日志：$log_file"
  fi
  echo "OK: Atlas 新版本已启动"
}

deb_writer_running() {
  ps -eo args= | grep '[d]pkg-deb -b' | grep -F -- "$DEB" >/dev/null
}

wait_for_deb() {
  local attempt previous_size=-1 current_size stable_invalid=0
  for attempt in {1..60}; do
    if deb_writer_running; then
      sleep 1
      continue
    fi
    current_size="$(stat -c '%s' "$DEB" 2>/dev/null || echo 0)"
    if [[ "$current_size" == "$previous_size" && "$current_size" -gt 0 ]]; then
      if dpkg-deb --info "$DEB" >/dev/null 2>&1 \
        && dpkg-deb --contents "$DEB" >/dev/null 2>&1; then
        return 0
      fi
      stable_invalid=$((stable_invalid + 1))
      [[ "$stable_invalid" -ge 3 ]] && return 1
    else
      stable_invalid=0
    fi
    previous_size="$current_size"
    sleep 1
  done
  return 1
}

echo "==> [1/3] 编译 + 测试 + 打包"
JAVA_HOME="${JAVA_HOME:-$HOME/jdk/jdk-17}"
GRADLE="${GRADLE:-$HOME/tools/gradle-8.14.3/bin/gradle}"
JAVA_HOME="$JAVA_HOME" "$GRADLE" test packageReleaseDeb --console=plain

DEB="$(find "$PWD/build/compose/binaries/main-release/deb" -maxdepth 1 -type f -name '*.deb' -printf '%T@ %p\n' 2>/dev/null | sort -nr | sed -n '1s/^[^ ]* //p')"
[[ -n "$DEB" ]] || die "没有找到 deb 打包产物"
echo "==> 等待并校验 deb 产物"
if ! wait_for_deb; then
  echo "! deb 产物未通过完整性校验，强制重新打包"
  JAVA_HOME="$JAVA_HOME" "$GRADLE" packageReleaseDeb --rerun-tasks --console=plain
  wait_for_deb || die "deb 打包产物未完成或已损坏：$DEB"
fi
stop_running_atlas
echo "==> [2/3] 安装 $DEB"
# 先移除旧版：版本号不变时 apt 会跳过本地 deb 安装
if dpkg-query -W -f='${Status}' atlas 2>/dev/null | grep -q 'install ok installed'; then
  sudo_run apt remove -y atlas
fi
sudo_run apt install -y "$DEB"

# X11/fcitx 下候选词窗跟随光标：JBR 新版 XIM 客户端（jb.awt.newXimClient）带原生
# adjustCandidatesNativeWindowPosition，默认关闭，不开则候选框固定在屏幕左下角
# （root-window 回退，2026-09-26 复现）；im.style=over-the-spot 是 09-23 的旧缓解，实测
# 不够，保留无害。compose 插件的 jpackage 配置不支持自定义 java-options，且 apt 重装
# 会覆盖 cfg，所以每次安装后补写；Main.kt 的 installImeCompatFlags 也注入了同样的值，
# 这里是打包应用双保险。
IME_FLAGS=(
  "java-options=-Djava.awt.im.style=over-the-spot"
  "java-options=-Djb.awt.newXimClient.enabled=true"
  "java-options=-Djb.awt.newXimClient.preferBelowTheSpot=true"
)
ATLAS_CFG="/opt/atlas/lib/app/atlas.cfg"
for ime_flag in "${IME_FLAGS[@]}"; do
  if ! sudo_run grep -qF "$ime_flag" "$ATLAS_CFG"; then
    sudo_run sed -i "/^\[JavaOptions\]/a $ime_flag" "$ATLAS_CFG"
    echo "==> 已注入输入法光标跟随参数：$ime_flag"
  fi
done

# 窗口 WM_CLASS 是 atlas-MainKt（Compose 默认取主类名），而 jpackage 生成的 desktop
# 不写 StartupWMClass，GNOME 匹配不上窗口，会把运行实例当另一个应用、多亮一个默认图标。
# desktop 文件每次安装都被覆盖，所以安装后补写；若改了主类名，这里的值要同步改（2026-09-23）。
ATLAS_DESKTOP="/usr/share/applications/atlas-atlas.desktop"
if sudo_run test -f "$ATLAS_DESKTOP" && ! sudo_run grep -q '^StartupWMClass=' "$ATLAS_DESKTOP"; then
  sudo_run sed -i '/^Icon=/a StartupWMClass=atlas-MainKt' "$ATLAS_DESKTOP"
  sudo_run update-desktop-database /usr/share/applications 2>/dev/null || true
  echo "==> 已注入 StartupWMClass=atlas-MainKt（窗口与启动器图标合一）"
fi

echo "==> [3/3] 验证"
# jpackage 的 deb 不建 PATH 链接，手工补一个，让终端可直接 atlas 启动
sudo_run ln -sf /opt/atlas/bin/atlas /usr/local/bin/atlas
dpkg -s atlas | grep -E '^(Status|Version)'
command -v atlas >/dev/null || die "Atlas 已安装，但 /usr/local/bin/atlas 不可执行"
launch_atlas
echo "OK: 菜单搜 Atlas 或终端运行 atlas 启动"

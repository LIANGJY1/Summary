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

echo "==> [3/3] 验证"
# jpackage 的 deb 不建 PATH 链接，手工补一个，让终端可直接 atlas 启动
sudo_run ln -sf /opt/atlas/bin/atlas /usr/local/bin/atlas
dpkg -s atlas | grep -E '^(Status|Version)'
command -v atlas >/dev/null || die "Atlas 已安装，但 /usr/local/bin/atlas 不可执行"
launch_atlas
echo "OK: 菜单搜 Atlas 或终端运行 atlas 启动"

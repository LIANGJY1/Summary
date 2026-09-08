#!/usr/bin/env bash
set -euo pipefail

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/fixproxy"

ssh_config="$($SCRIPT --print-ssh-config 192.168.222.8)"
grep -Fq 'Host github.com' <<<"$ssh_config"
grep -Fq 'HostName ssh.github.com' <<<"$ssh_config"
grep -Fq 'Port 443' <<<"$ssh_config"
grep -Fq 'nc -X connect -x 192.168.222.8:7990 %h %p' <<<"$ssh_config"

git_proxy="$($SCRIPT --print-git-proxy 192.168.222.8)"
[ "$git_proxy" = 'http://192.168.222.8:7990' ]

test_home=$(mktemp -d)
HOME="$test_home" git config --global http.proxy http://192.0.2.1:7990
HOME="$test_home" "$SCRIPT" --clear-git-proxy
if HOME="$test_home" git config --global --get http.proxy >/dev/null 2>&1; then
    echo 'old Git proxy was not cleared' >&2
    exit 1
fi

echo 'test_fixproxy: PASS'

#!/usr/bin/env bash
# 一键镜像同步：~/.agents/skills → Summary/skills（git 管理副本）
#
# 决策记录（2026-09-06 与用户确认）：
#   1. 镜像删除（--delete）：副本永远与源一致；误删/改名可在 git 历史找回
#   2. 不自动 commit：同步完输出 git status 摘要，由用户确认后手动固化
#   3. 排除项仅限脚本自身、根 README* 与《调研指南》git 管理副本（均锚定根，不影响 skill 内部同名文件）；.git 一律不进副本
#
# 用法: ./sync-from-agents.sh   （可在任意目录执行，路径取脚本所在位置）
set -euo pipefail

SRC="${HOME}/.agents/skills"
SELF="sync-from-agents.sh"
DST="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(git -C "$DST" rev-parse --show-toplevel 2>/dev/null || dirname "$DST")"

[ -d "$SRC" ] || { echo "✗ 源目录不存在: $SRC"; exit 1; }

before=$(ls "$DST" | sort)
echo "同步 $SRC → $DST"
rsync -a --delete --itemize-changes \
  --exclude="/$SELF" --exclude="/README*" --exclude="/全网高质量Skills调研与写作指南.md" --exclude=".git" \
  "$SRC/" "$DST/" > /tmp/sync-skills-itemize.txt

after=$(ls "$DST" | sort)

# 统计：目录级增删（按行数）+ 文件级增改删（来自 rsync itemize）
line_count() { [ -n "$1" ] && echo "$1" | grep -c . || echo 0; }
added=$(comm -13 <(echo "$before") <(echo "$after") || true)
removed=$(comm -23 <(echo "$before") <(echo "$after") || true)
updated=$(grep -c '^>f' /tmp/sync-skills-itemize.txt || true)
deleted_files=$(grep -c '^\*deleting' /tmp/sync-skills-itemize.txt || true)

echo "── 同步结果 ─────────────────────────────"
echo "目录新增 ($(line_count "$added")):";   [ -n "$added" ]   && echo "$added"   | sed 's/^/  + /'
echo "目录移除 ($(line_count "$removed")):"; [ -n "$removed" ] && echo "$removed" | sed 's/^/  - /'
echo "文件新增/更新: $updated 个；文件删除: $deleted_files 个"
[ -z "$added" ] && [ -z "$removed" ] && [ "$updated" = "0" ] && echo "  （无变化，副本已是最新）"

echo "── git status（Summary 仓库，未自动 commit）──"
git -C "$REPO_ROOT" status --short -- "$DST" | head -30
echo "提醒: 确认无误后请手动 commit 固化（本脚本不自动 commit）"

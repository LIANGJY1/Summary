#!/usr/bin/env bash
# project-decoder 定向阶段统计脚本（SKILL.md §1 用，只读）。
# 用法: repo_stats.sh <仓库根> [churn条数，默认20]
# 输出: 语言分布 / 最大文件 Top / 变更频次 Top(churn) / 热点交集(大×常改)
# 依赖: git。非 git 目录时 churn 节自动跳过。
set -euo pipefail

ROOT="${1:?用法: repo_stats.sh <仓库根> [churn条数]}"
TOP="${2:-20}"
cd "$ROOT"

echo "=== 语言/文件分布 (Top 10 扩展名) ==="
find . -type f -not -path '*/.git/*' -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/dist/*' -not -path '*/.gradle/*' -not -path '*/target/*' \
  | awk -F. 'NF>1{print $NF}' | sort | uniq -c | sort -rn | head -10

echo; echo "=== 源码文件总数(排除产物目录) ==="
find . -type f -not -path '*/.git/*' -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/dist/*' -not -path '*/.gradle/*' -not -path '*/target/*' | wc -l

echo; echo "=== 最大文件 Top $TOP (行数) ==="
find . -type f \( -name '*.java' -o -name '*.kt' -o -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.py' -o -name '*.go' -o -name '*.rs' -o -name '*.c' -o -name '*.cpp' -o -name '*.cs' -o -name '*.rb' \) \
  -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/dist/*' -not -path '*/target/*' -exec wc -l {} + 2>/dev/null | sort -rn | head -"$TOP" | grep -v ' total$'

if git rev-parse --git-dir >/dev/null 2>&1; then
  echo; echo "=== 变更频次 Top $TOP (近 1000 次提交) ==="
  git log --format= --name-only -1000 -- . | grep -v '^$' | sort | uniq -c | sort -rn | head -"$TOP"
  echo; echo "=== 热点交集: 常改 × 大文件 (重点解码对象) ==="
  comm -12 \
    <(git log --format= --name-only -1000 -- . | grep -v '^$' | sort | uniq -c | sort -rn | head -"$TOP" | awk '{print $2}' | sort) \
    <(find . -type f \( -name '*.java' -o -name '*.kt' -o -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.py' -o -name '*.go' -o -name '*.rs' \) \
      -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/target/*' -exec wc -l {} + 2>/dev/null | sort -rn | head -"$TOP" | grep -v ' total$' | awk '{$1=""; print substr($0,2)}' | sort)
else
  echo; echo "=== (非 git 仓库，跳过 churn 与热点交集) ==="
fi

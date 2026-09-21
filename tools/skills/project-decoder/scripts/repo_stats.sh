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
  DEPTH=$(git rev-list --count HEAD 2>/dev/null || echo 0)
  if [ "$DEPTH" -lt 20 ]; then
    # 历史深度守门：浅历史(导入式检出/新仓)的 churn 是噪音，静默输出会误导热点定位
    echo; echo "=== (git 历史仅 $DEPTH 次提交 < 20：churn 与热点交集不可信，已跳过——热点定位改用上方『最大文件 Top』) ==="
  else
    echo; echo "=== 变更频次 Top $TOP (近 1000 次提交) ==="
    git log --format= --name-only -1000 -- . | grep -v '^$' | sort | uniq -c | sort -rn | head -"$TOP"
    echo; echo "=== 热点交集: 常改 × 大文件 (重点解码对象) ==="
    comm -12 \
      <(git log --format= --name-only -1000 -- . | grep -v '^$' | sort | uniq -c | sort -rn | head -"$TOP" | awk '{print $2}' | sort) \
      <(find . -type f \( -name '*.java' -o -name '*.kt' -o -name '*.ts' -o -name '*.tsx' -o -name '*.js' -o -name '*.py' -o -name '*.go' -o -name '*.rs' \) \
        -not -path '*/node_modules/*' -not -path '*/build/*' -not -path '*/dist/*' -not -path '*/target/*' -exec wc -l {} + 2>/dev/null | sort -rn | head -"$TOP" | grep -v ' total$' | awk '{$1=""; print substr($0,2)}' | sort)
  fi
else
  echo; echo "=== (非 git 仓库，跳过 churn 与热点交集) ==="
fi

echo; echo "=== git 历史有效性探测 ==="
if git rev-parse --git-dir >/dev/null 2>&1; then
  N=$(git rev-list --count HEAD 2>/dev/null || echo 0)
  if [ "$N" -lt 10 ]; then
    echo "⚠ 历史不可考：仅 $N 个 commit（<10）。动机考据跳过 git 流水线，改走代码结构+测试名，缺失记入开放问题。"
  else
    UNIQ=$(git log --format=%s -1000 -- . | sort -u | wc -l)
    TOT=$(git log --oneline -1000 -- . | wc -l)
    RATIO=$(( TOT > 0 ? UNIQ * 100 / TOT : 0 ))
    if [ "$RATIO" -lt 30 ]; then
      echo "⚠ 历史可疑：近 ${TOT} 条 commit 中去重 message 仅 ${UNIQ}（${RATIO}%<30%），churn 热点与考据结论慎用。"
    else
      echo "历史可用：${TOT} commits，message 去重率 ${RATIO}%。churn/热点交集可信。"
      echo "（注：去重率高≠语义有效，message 全为 add/fix 类同质词时仍视为不可考，人工复核。）"
    fi
  fi
fi

echo; echo "=== 主代码 vs 测试代码对照（测试即规范成熟度） ==="
MAIN_F=$(find . -type f \( -name '*.java' -o -name '*.kt' \) -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/node_modules/*' \( -path '*/src/test/*' -o -path '*/java-test/*' -o -path '*/src/androidTest/*' \) -prune -o -type f -print 2>/dev/null | wc -l)
MAIN_F=$(find . -type f \( -name '*.java' -o -name '*.kt' \) -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/node_modules/*' -not -path '*/src/test/*' -not -path '*/java-test/*' -not -path '*/src/androidTest/*' -not -path '*/kotlin-test/*' | wc -l)
TEST_F=$(find . -type f \( -name '*.java' -o -name '*.kt' \) -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/node_modules/*' \( -path '*/src/test/*' -o -path '*/java-test/*' -o -path '*/src/androidTest/*' -o -path '*/kotlin-test/*' \) | wc -l)
MAIN_L=$(find . -type f \( -name '*.java' -o -name '*.kt' \) -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/node_modules/*' -not -path '*/src/test/*' -not -path '*/java-test/*' -not -path '*/src/androidTest/*' -not -path '*/kotlin-test/*' -exec cat {} + 2>/dev/null | wc -l)
TEST_L=$(find . -type f \( -name '*.java' -o -name '*.kt' \) -not -path '*/.git/*' -not -path '*/build/*' -not -path '*/node_modules/*' \( -path '*/src/test/*' -o -path '*/java-test/*' -o -path '*/src/androidTest/*' -o -path '*/kotlin-test/*' \) -exec cat {} + 2>/dev/null | wc -l)
echo "主代码: ${MAIN_F} 文件 / ${MAIN_L} 行；测试: ${TEST_F} 文件 / ${TEST_L} 行"
[ "$MAIN_L" -gt 0 ] && echo "测试/主码行数比: $(( TEST_L * 100 / MAIN_L ))%（>60% 说明行为契约主要靠测试承载，考据优先读测试名）"

echo; echo "=== 枢纽引用计数 Top 15（定义于主源码、被其他文件引用的类） ==="
python3 - "$@" << 'PYEOF2'
import subprocess, sys, re, os, collections
args = sys.argv[1:]
exts = ('.java', '.kt')
skip = ('/src/test/', '/java-test/', '/src/androidTest/', '/kotlin-test/', '/build/', '/.git/', '/node_modules/')
files = []
for root, dirs, fs in os.walk(args[0] if args else '.'):
    if any(x in root for x in skip): continue
    for f in fs:
        if f.endswith(exts): files.append(os.path.join(root, f))
defs = collections.defaultdict(list)
pat = re.compile(r'(?m)^\s*(?:public |private |internal |final |abstract |open |data |sealed |enum )*'
                 r'(?:class|interface|enum|object)\s+([A-Z][A-Za-z0-9_]+)')
texts = {}
for f in files:
    try: t = open(f, encoding='utf-8', errors='ignore').read()
    except OSError: continue
    texts[f] = t
    for m in pat.finditer(t): defs[m.group(1)].append(f)
hits = []
for name, deffs in defs.items():
    n = sum(1 for f, t in texts.items() if name in t and f not in deffs)
    if n > 2: hits.append((n, name))
for n, name in sorted(hits, reverse=True)[:15]:
    print(f'{n} {name}')
PYEOF2

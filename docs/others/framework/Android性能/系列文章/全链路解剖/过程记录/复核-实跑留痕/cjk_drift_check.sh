#!/usr/bin/env bash
# R7 自验：study 全部改动源码文件 剥CJK 零代码漂移复核
# 方法复现 批12-过程记录 §七.1：grep -v '[一-龥]' 行级剥 CJK（精树→study），diff 的 d/c 操作必须为 0
set -u
PRISTINE=/home/liang/Project/AAOS13
STUDY=/home/liang/Project/MyProject/AAOS13_study
OUT=/tmp/review-applist/r7/cjk_drift
mkdir -p "$OUT"
cd "$STUDY" || exit 1
total=0; drift=0
: > "$OUT/summary.txt"
while IFS= read -r f; do
  [ -z "$f" ] && continue
  total=$((total+1))
  p="$PRISTINE/$f"; s="$STUDY/$f"
  if [ ! -f "$p" ]; then echo "MISSING-PRISTINE $f" >> "$OUT/summary.txt"; drift=$((drift+1)); continue; fi
  grep -v '[一-龥]' "$p" > "$OUT/p.tmp"
  grep -v '[一-龥]' "$s" > "$OUT/s.tmp"
  diff "$OUT/p.tmp" "$OUT/s.tmp" > "$OUT/d.tmp"
  # d/c 命令行（删除/变更）：形如  NcM / NdM / N,McM
  dc=$(grep -Ec '^[0-9]+(,[0-9]+)?[cd]' "$OUT/d.tmp")
  ins=$(grep -Ec '^[0-9]+(,[0-9]+)?a' "$OUT/d.tmp")
  if [ "$dc" -ne 0 ]; then
    drift=$((drift+1))
    echo "DRIFT d/c=$dc ins=$ins  $f" >> "$OUT/summary.txt"
    cp "$OUT/d.tmp" "$OUT/drift_$(echo "$f" | tr '/' '_').diff"
  else
    echo "OK    d/c=0 ins=$ins  $f" >> "$OUT/summary.txt"
  fi
done < <(git status --porcelain | grep -E "\.(java|cpp|c|h|cc)$" | grep -v "docs/" | sed 's/^ M //')
echo "==== TOTAL files=$total  drift_files=$drift ====" >> "$OUT/summary.txt"
cat "$OUT/summary.txt"
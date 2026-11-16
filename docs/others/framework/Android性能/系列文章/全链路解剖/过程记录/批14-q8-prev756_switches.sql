-- 批14 Q8: 主线程 tid 756 在 6.80–6.83 s 全部 sched_switch 切出事件（prev_state 数值形态）
SELECT raw.ts - 6073694444196 AS rel_ts, raw.cpu AS cpu,
       EXTRACT_ARG(raw.arg_set_id, 'prev_state') AS prev_state,
       EXTRACT_ARG(raw.arg_set_id, 'next_comm') AS next_comm,
       EXTRACT_ARG(raw.arg_set_id, 'next_pid') AS next_pid
FROM raw
WHERE raw.name = 'sched_switch'
  AND EXTRACT_ARG(raw.arg_set_id, 'prev_pid') = 756
  AND raw.ts - 6073694444196 BETWEEN 6805000000 AND 6832000000
ORDER BY raw.ts LIMIT 40;

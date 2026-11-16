-- 批14 Q7: 主线程 tid 756 在窗口 2 边界（rel 6,812,790,111 前后）的 raw sched_switch 原文
SELECT raw.ts - 6073694444196 AS rel_ts, raw.name AS event, raw.cpu AS cpu,
       EXTRACT_ARG(raw.arg_set_id, 'prev_comm') AS prev_comm,
       EXTRACT_ARG(raw.arg_set_id, 'prev_pid') AS prev_pid,
       EXTRACT_ARG(raw.arg_set_id, 'prev_state') AS prev_state,
       EXTRACT_ARG(raw.arg_set_id, 'next_comm') AS next_comm,
       EXTRACT_ARG(raw.arg_set_id, 'next_pid') AS next_pid
FROM raw
WHERE raw.name = 'sched_switch'
  AND raw.cpu = 2
  AND raw.ts - 6073694444196 BETWEEN 6812700000 AND 6813000000
ORDER BY raw.ts LIMIT 40;

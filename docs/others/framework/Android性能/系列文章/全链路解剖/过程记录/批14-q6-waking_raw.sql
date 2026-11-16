-- 批14 Q6: raw 表 sched_waking / sched_wakeup 事件复核（InputReader tid 1489 与主线程 tid 756 的唤醒点）
SELECT raw.ts - 6073694444196 AS rel_ts, raw.name AS event, raw.cpu AS cpu, s.tid AS tid, s.name AS comm,
       EXTRACT_ARG(raw.arg_set_id, 'pid') AS pid,
       EXTRACT_ARG(raw.arg_set_id, 'target_cpu') AS target_cpu,
       EXTRACT_ARG(raw.arg_set_id, 'prio') AS prio
FROM raw LEFT JOIN thread s ON raw.utid = s.utid
WHERE raw.name IN ('sched_waking','sched_wakeup')
  AND EXTRACT_ARG(raw.arg_set_id, 'pid') IN (1489, 756)
  AND raw.ts - 6073694444196 BETWEEN 6787000000 AND 6796600000
ORDER BY raw.ts LIMIT 30;

SELECT '---waking around main thread R2 start 6,812,790,111---' AS marker;

SELECT raw.ts - 6073694444196 AS rel_ts, raw.name AS event, raw.cpu AS cpu, s.tid AS tid, s.name AS comm,
       EXTRACT_ARG(raw.arg_set_id, 'pid') AS pid,
       EXTRACT_ARG(raw.arg_set_id, 'target_cpu') AS target_cpu
FROM raw LEFT JOIN thread s ON raw.utid = s.utid
WHERE raw.name IN ('sched_waking','sched_wakeup')
  AND EXTRACT_ARG(raw.arg_set_id, 'pid') = 756
  AND raw.ts - 6073694444196 BETWEEN 6812000000 AND 6830200000
ORDER BY raw.ts LIMIT 30;

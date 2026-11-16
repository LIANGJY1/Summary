-- 批14 Q9: 主线程两次 prev_state=0 切出（6,805,709,380 / 6,812,790,111）前后 1ms 内 next_pid 的 sched_waking
SELECT raw.ts - 6073694444196 AS rel_ts, raw.name AS event, raw.cpu AS cpu, s.tid AS emitter_tid, s.name AS emitter_comm,
       EXTRACT_ARG(raw.arg_set_id, 'pid') AS wakee_pid,
       EXTRACT_ARG(raw.arg_set_id, 'target_cpu') AS target_cpu
FROM raw LEFT JOIN thread s ON raw.utid = s.utid
WHERE raw.name IN ('sched_waking','sched_wakeup')
  AND EXTRACT_ARG(raw.arg_set_id, 'pid') IN (1136, 26884)
  AND raw.ts - 6073694444196 BETWEEN 6805690000 AND 6805720000
ORDER BY raw.ts LIMIT 20;

SELECT '---window2 boundary---' AS marker;

SELECT raw.ts - 6073694444196 AS rel_ts, raw.name AS event, raw.cpu AS cpu, s.tid AS emitter_tid, s.name AS emitter_comm,
       EXTRACT_ARG(raw.arg_set_id, 'pid') AS wakee_pid,
       EXTRACT_ARG(raw.arg_set_id, 'target_cpu') AS target_cpu
FROM raw LEFT JOIN thread s ON raw.utid = s.utid
WHERE raw.name IN ('sched_waking','sched_wakeup')
  AND EXTRACT_ARG(raw.arg_set_id, 'pid') IN (1136, 26884)
  AND raw.ts - 6073694444196 BETWEEN 6812780000 AND 6812810000
ORDER BY raw.ts LIMIT 20;

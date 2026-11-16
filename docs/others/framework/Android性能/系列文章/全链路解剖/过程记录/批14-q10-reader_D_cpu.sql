-- 批14 Q10: InputReader tid 1489 在 D 窗口前后的状态序列（含 cpu）与 D 窗口内其 sched 活动查证
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.cpu, ts.blocked_function
FROM thread_state ts
WHERE ts.utid = (SELECT utid FROM thread WHERE tid=1489)
  AND ts.ts - 6073694444196 BETWEEN 6799000000 AND 6806200000
ORDER BY ts.ts LIMIT 30;

SELECT '---sched slices of tid 1489 in 6,801,836,572-6,805,553,072 (D window)---' AS marker;

SELECT sched.ts - 6073694444196 AS rel_ts, sched.dur
FROM sched
WHERE sched.utid = (SELECT utid FROM thread WHERE tid=1489)
  AND sched.ts - 6073694444196 BETWEEN 6801836572 AND 6805553072
ORDER BY sched.ts LIMIT 10;

SELECT '---sched slices of tid 1489 just before D---' AS marker;

SELECT sched.ts - 6073694444196 AS rel_ts, sched.dur, sched.cpu
FROM sched
WHERE sched.utid = (SELECT utid FROM thread WHERE tid=1489)
  AND sched.ts - 6073694444196 BETWEEN 6800700000 AND 6801900000
ORDER BY sched.ts LIMIT 10;

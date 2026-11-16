-- Q6: 主线程 tid=756 与 binder:756_2 tid=824 的 thread_state（6.795–6.832s，主线程含 waker）
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.waker_utid, wt.name AS waker_thread, wt.tid AS waker_tid
FROM thread_state ts LEFT JOIN thread wt ON ts.waker_utid = wt.utid
WHERE ts.utid = (SELECT utid FROM thread WHERE tid=756)
  AND ts.ts - 6073694444196 BETWEEN 6795000000 AND 6832500000
ORDER BY ts.ts LIMIT 30;

SELECT '---binder824---' AS marker;
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.waker_utid, wt.name AS waker_thread, wt.tid AS waker_tid
FROM thread_state ts LEFT JOIN thread wt ON ts.waker_utid = wt.utid
WHERE ts.utid = (SELECT utid FROM thread WHERE tid=824)
  AND ts.ts - 6073694444196 BETWEEN 6796300000 AND 6830400000
ORDER BY ts.ts LIMIT 40;

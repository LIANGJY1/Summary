-- 批14 Q1: 主线程 tid=756 状态序列（含 cpu 列），定位两个 R 窗口所在 CPU
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.cpu, ts.waker_utid, wt.name AS waker_thread, wt.tid AS waker_tid
FROM thread_state ts LEFT JOIN thread wt ON ts.waker_utid = wt.utid
WHERE ts.utid = (SELECT utid FROM thread WHERE tid=756)
  AND ts.ts - 6073694444196 BETWEEN 6795000000 AND 6832500000
ORDER BY ts.ts LIMIT 40;

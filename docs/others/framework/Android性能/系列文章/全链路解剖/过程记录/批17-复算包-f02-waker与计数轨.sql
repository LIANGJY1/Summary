-- 证据包#11：Launcher 主线程醒点 waker 直连
SELECT 'waker' AS tag, ts.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, ts.dur, ts.state, ts.waker_utid,
       wt.tid AS waker_tid, wt.name AS waker_thread, wp.name AS waker_proc
FROM thread_state ts
LEFT JOIN thread wt ON ts.waker_utid = wt.utid
LEFT JOIN process wp ON wt.upid = wp.upid
WHERE ts.utid = (SELECT utid FROM thread WHERE tid = 1909)
  AND ts.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6830700000 AND 6830900000
ORDER BY ts.ts;

-- 证据包#12：iq/oq/wq 计数轨采样（事件分发窗口）
SELECT 'oqwq' AS tag, (c.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, ct.name, c.value AS val
FROM counter c JOIN counter_track ct ON ct.id = c.track_id
WHERE (ct.name LIKE 'oq%' OR ct.name LIKE 'wq%' OR ct.name = 'iq')
  AND c.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6820000000 AND 6860000000
ORDER BY c.ts, ct.name;

-- 证据包#6（重叠裁剪口径）：CPU3 在 InputReader R 7.951 ms 窗口的逐线程裁剪统计
WITH win AS (
  SELECT (SELECT start_ts FROM trace_bounds) + 6787426919 AS w0,
         (SELECT start_ts FROM trace_bounds) + 6787426919 + 7951192 AS w1)
SELECT 'cpu3_overlap_trimmed' AS tag, th.tid, th.name,
       SUM(MAX(MIN(s.ts + s.dur, w.w1), 0) - MAX(s.ts, w.w0)) AS runtime_ns,
       COUNT(*) AS slices
FROM sched s JOIN thread th USING(utid), win w
WHERE s.cpu = 3 AND s.ts < w.w1 AND s.ts + s.dur > w.w0
GROUP BY th.utid ORDER BY runtime_ns DESC LIMIT 10;

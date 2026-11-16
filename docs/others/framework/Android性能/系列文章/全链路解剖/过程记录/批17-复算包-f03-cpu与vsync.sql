-- 证据包（批14）：Launcher 主线程 S 窗口所在 CPU 与 CPU4 idle 裁剪统计
SELECT 'launcher_S_cpu' AS tag, ts.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, ts.dur, ts.state, ts.cpu
FROM thread_state ts
WHERE ts.utid = (SELECT utid FROM thread WHERE tid = 1909)
  AND ts.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6779000000 AND 6831000000
ORDER BY ts.ts LIMIT 5;

-- CPU4 在 S 窗口（rel 6,779,836,842–6,830,764,842）线程构成（重叠裁剪求和）
SELECT 'cpu4_s_window' AS tag, s.tid AS tid, s.name AS name,
       SUM(MIN(6073694444196 + 6830764842, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6779836842)) AS clipped,
       COUNT(*) AS slices
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 4 AND sched.ts < 6073694444196 + 6830764842 AND sched.ts + sched.dur > 6073694444196 + 6779836842
GROUP BY s.tid, s.name ORDER BY clipped DESC LIMIT 5;

-- 批12：VSYNC 计数轨样本
SELECT 'vsync_counters' AS tag, ct.name AS counter_name, c.ts - 6073694444196 AS rel_ts, c.value
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name IN ('VSYNC-sf','VSYNC-app','HW_VSYNC_0')
  AND c.ts - 6073694444196 >= 6700000000
  AND c.ts - 6073694444196 <= 6720000000
ORDER BY c.ts LIMIT 12;

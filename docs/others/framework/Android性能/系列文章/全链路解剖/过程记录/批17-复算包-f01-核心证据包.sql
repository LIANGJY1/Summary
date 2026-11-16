SELECT 'bounds' AS tag, start_ts, end_ts - start_ts AS span FROM trace_bounds;

-- 证据包#1/#9：Launcher 主线程（tid 1909）S 窗口
SELECT 'launcher_S' AS tag, ts.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, ts.dur, ts.state
FROM thread_state ts
WHERE ts.utid = (SELECT utid FROM thread WHERE tid = 1909) AND ts.dur > 40000000
  AND ts.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6700000000 AND 6900000000
ORDER BY ts.ts;

-- 证据包#4/#5：InputDispatcher（tid 1488）S 窗口
SELECT 'dispatcher_S' AS tag, ts.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, ts.dur, ts.state
FROM thread_state ts
WHERE ts.utid = (SELECT utid FROM thread WHERE tid = 1488) AND ts.dur > 40000000
  AND ts.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6700000000 AND 6900000000
ORDER BY ts.ts;

-- 证据包#7/#8：InputReader（tid 1489）R/D 序列
SELECT 'reader_states' AS tag, ts.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, ts.dur, ts.state, ts.blocked_function
FROM thread_state ts
WHERE ts.utid = (SELECT utid FROM thread WHERE tid = 1489)
  AND ts.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6780000000 AND 6820000000
ORDER BY ts.ts;

-- 证据包#2/#10：两帧 doFrame 与同 ID 事件链 slice
SELECT 'id_chain' AS tag, s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name, th.tid
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread th ON th.utid = tt.utid
WHERE (s.name LIKE '%0x3b3b7f02%' OR s.name = 'Choreographer#doFrame 155803' OR s.name = 'Choreographer#doFrame 155812'
       OR s.name = 'Choreographer#scheduleVsyncLocked' OR s.name = 'RV Scroll')
  AND s.ts - (SELECT start_ts FROM trace_bounds) BETWEEN 6700000000 AND 6900000000
ORDER BY s.ts;

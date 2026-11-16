-- 批10 q2：RenderThread 线程上的 DrawFrames slice（相对时间，逐 ns）
-- rel = ts - (SELECT start_ts FROM trace_bounds)
SELECT s.id, s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name, t.tid, t.name AS thread_name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
JOIN process p ON tt.utid = t.utid AND t.upid = p.upid
WHERE p.name LIKE '%yadea.launcher%'
  AND s.name LIKE 'DrawFrames%'
ORDER BY s.ts
LIMIT 60;

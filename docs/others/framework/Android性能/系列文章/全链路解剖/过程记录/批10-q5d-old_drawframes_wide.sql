-- 批10 q5d：旧样本精确 dur=2280038 的 DrawFrames（放宽窗口 2280038±5000）
SELECT s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 2107 AND s.name LIKE 'DrawFrames %'
  AND s.dur BETWEEN 2275038 AND 2285038
ORDER BY s.ts LIMIT 10;

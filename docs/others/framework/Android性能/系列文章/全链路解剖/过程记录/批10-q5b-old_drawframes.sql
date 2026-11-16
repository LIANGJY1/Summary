-- 批10 q5b：旧样本 DrawFrames dur 2.280038ms 与 doFrame dur 531807ns 的端点与重叠
-- DrawFrames
SELECT 'DrawFrames' AS kind, s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 2107 AND s.name LIKE 'DrawFrames %'
  AND s.dur BETWEEN 2200000 AND 2400000
ORDER BY s.ts LIMIT 10;

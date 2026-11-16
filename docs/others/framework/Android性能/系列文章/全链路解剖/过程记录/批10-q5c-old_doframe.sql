-- 批10 q5c：旧样本主线程 doFrame dur≈531807 的 slice 与同名 DrawFrames（vsyncId 配对）
SELECT 'doFrame' AS kind, s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 1913 AND s.name LIKE 'Choreographer#doFrame %'
  AND s.dur BETWEEN 500000 AND 560000
ORDER BY s.ts LIMIT 10;

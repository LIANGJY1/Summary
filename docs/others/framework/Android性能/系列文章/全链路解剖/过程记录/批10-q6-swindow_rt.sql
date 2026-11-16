-- 批10 q6：S 窗口（rel 6,779,837,000~6,830,765,000）内 RenderThread(tid 2079) 全部 slice
SELECT s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 2079
  AND (s.ts - (SELECT start_ts FROM trace_bounds)) BETWEEN 6779837000 AND 6830765000
ORDER BY s.ts;

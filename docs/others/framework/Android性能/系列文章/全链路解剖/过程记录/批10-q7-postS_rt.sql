-- 批10 q7：S 结束（6,830,765,000）到 DrawFrames 155812 起（6,842,332,265）之间 tid 2079 全部 slice
SELECT s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 2079
  AND (s.ts - (SELECT start_ts FROM trace_bounds)) BETWEEN 6830765000 AND 6842332266
ORDER BY s.ts;

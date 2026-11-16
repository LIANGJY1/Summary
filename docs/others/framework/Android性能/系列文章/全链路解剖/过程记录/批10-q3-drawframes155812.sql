-- 批10 q3（修正版）：rel 窗口 6.84s~6.88s 内 RenderThread(tid 2079) 全部 slice（相对时间，逐 ns）
SELECT s.id, s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name, t.tid
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 2079
  AND (s.ts - (SELECT start_ts FROM trace_bounds)) BETWEEN 6840000000 AND 6880000000
ORDER BY s.ts;

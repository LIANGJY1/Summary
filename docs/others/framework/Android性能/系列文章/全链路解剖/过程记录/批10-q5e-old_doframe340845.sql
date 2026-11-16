-- 批10 q5e：旧样本 vsyncId 340845 的主线程 doFrame 精确端点
SELECT s.ts - (SELECT start_ts FROM trace_bounds) AS rel_ts, s.dur, s.name, t.tid
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 1913 AND s.name = 'Choreographer#doFrame 340845'
ORDER BY s.ts;

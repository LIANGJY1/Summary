-- 批11 q4: 关键窗口（rel 6.75s~6.91s）tid 2079 RenderThread + tid 1909 主线程上
-- buffer 操作 slice 与 doFrame/postAndWait 配对（rel 基准 trace_bounds.start_ts）
SELECT s.ts - (SELECT start_ts FROM trace_bounds LIMIT 1) AS rel_ts,
       s.dur, t.tid, t.name AS thread_name, s.name AS slice_name, s.depth
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name LIKE '%yadea.launcher%'
  AND t.tid IN (2079, 1909)
  AND (SELECT start_ts FROM trace_bounds LIMIT 1) + 6750000000 <= s.ts
  AND s.ts <= (SELECT start_ts FROM trace_bounds LIMIT 1) + 9100000000
  AND (s.name IN ('dequeueBuffer','queueBuffer','acquireBuffer','releaseBuffer')
       OR s.name LIKE '%doFrame%' OR s.name LIKE '%postAndWait%'
       OR s.name LIKE '%Record%' OR s.name LIKE '%traversal%' OR s.name = 'draw'
       OR s.name LIKE '%- VRI%')
ORDER BY s.ts LIMIT 120;

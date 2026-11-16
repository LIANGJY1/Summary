-- 批11 q1: com.yadea.launcher 进程内 BLAST/transaction/buffer 类 slice 全量扫描
-- rel 基准 trace_bounds.start_ts（注意: trace_bounds 无 ts 列，标量子查询写 MAX(ts)
-- 会静默降级为 NULL——本查询首版踩坑，输出只有 1 行，已改 start_ts 重跑）
SELECT s.ts - (SELECT start_ts FROM trace_bounds LIMIT 1) AS rel_ts,
       s.dur, t.tid, t.name AS thread_name, s.name AS slice_name, s.depth
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name LIKE '%yadea.launcher%'
  AND (s.name LIKE '%BLAST%' OR s.name LIKE '%Blast%'
       OR s.name LIKE '%ransaction%'
       OR s.name LIKE '%queueBuffer%' OR s.name LIKE '%equeue%'
       OR s.name LIKE '%cquire%' OR s.name LIKE '%elease%'
       OR s.name LIKE '%QueuedBuffer%' OR s.name LIKE '%PendingRelease%'
       OR s.name LIKE '%onFrameAvailable%')
ORDER BY s.ts LIMIT 20000;

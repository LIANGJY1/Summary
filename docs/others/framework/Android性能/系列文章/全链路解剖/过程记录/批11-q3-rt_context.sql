-- 批11 q3: RenderThread tid 2079 轨上 trace 起点附近全部 slice（看 dequeueBuffer 父链）
-- 与全 trace 各进程 dequeueBuffer/queueBuffer slice 计数
SELECT s.ts - (SELECT MAX(ts) FROM trace_bounds LIMIT 1) AS rel_ts,
       s.dur, t.tid, t.name AS thread_name, s.name AS slice_name, s.depth
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE p.name LIKE '%yadea.launcher%' AND t.tid = 2079
ORDER BY s.ts LIMIT 30;

SELECT t.tid, t.name AS thread_name, p.name AS process_name, s.name AS slice_name, COUNT(*) AS cnt
FROM slice s
JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t ON tt.utid = t.utid
JOIN process p ON t.upid = p.upid
WHERE s.name IN ('dequeueBuffer','queueBuffer','acquireBuffer','releaseBuffer',
                 'requestBuffer','cancelBuffer','latchBuffer','setBuffer')
GROUP BY t.tid, t.name, p.name, s.name
ORDER BY cnt DESC LIMIT 50;

-- 批13 q4: "GPU completion"/"PrevGpuFrameMissed" 轨上的 slice 明细（判定其归属域）
SELECT s.ts, s.dur, s.name, t.name AS track_name, p.name AS process_name, thread.name AS thread_name
FROM slice s
JOIN track t ON s.track_id = t.id
LEFT JOIN process_track pt ON pt.id = t.id LEFT JOIN process p ON pt.upid = p.upid
LEFT JOIN thread_track tt ON tt.id = t.id LEFT JOIN thread ON tt.utid = thread.utid
WHERE t.name IN ('GPU completion', 'PrevGpuFrameMissed')
ORDER BY s.ts;

-- sched 表规模（证明 sched 事件面在采）
SELECT COUNT(*) AS sched_rows FROM sched;

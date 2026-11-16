-- 批13 q3: stats 表中 ftrace setup errors 与数据包统计；以及本 trace 实际存在的 ftrace 事件面抽样
SELECT name, severity, value
FROM stats
WHERE name LIKE '%error%' OR name LIKE '%ftrace%' OR name LIKE '%traced_buf%'
ORDER BY name;

-- 本 trace 中有数据的 sched/irq 抽样（验证采集配置面）
SELECT 'sched_slices' AS src, COUNT(*) AS n FROM slice JOIN thread_track ON slice.track_id = thread_track.id WHERE slice.name LIKE 'sched%'
UNION ALL
SELECT 'counter_tracks', COUNT(*) FROM counter_track
UNION ALL
SELECT 'all_tracks', COUNT(*) FROM track;

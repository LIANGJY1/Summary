-- 批11 q2: 全 trace counter 轨名扫描（找 BLAST 队列计数与 aq:* 类计数）
-- 判据: counter_track.name 含 aq/QueuedBuffer/PendingRelease/BLAST/Consumer 或
--       归属 com.yadea.launcher 进程的全部 counter_track 列名清单
SELECT DISTINCT ct.name AS counter_name, p.name AS process_name, t.tid
FROM counter_track ct
LEFT JOIN process_counter_track pct ON ct.id = pct.id
LEFT JOIN process p ON pct.upid = p.upid
LEFT JOIN thread_counter_track tct ON ct.id = tct.id
LEFT JOIN thread t ON tct.utid = t.utid
WHERE ct.name LIKE '%aq:%'
   OR ct.name LIKE '%QueuedBuffer%'
   OR ct.name LIKE '%PendingRelease%'
   OR ct.name LIKE '%BLAST%'
   OR ct.name LIKE '%Blast%'
   OR (p.name LIKE '%yadea.launcher%')
ORDER BY ct.name LIMIT 200;

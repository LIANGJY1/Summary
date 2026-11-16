-- 批11 q5: 四条计数轨在关键窗口（rel 6.75s~6.91s）的取值序列
SELECT c.ts - (SELECT start_ts FROM trace_bounds LIMIT 1) AS rel_ts,
       ct.name AS counter_name, c.value
FROM counter c
JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name IN ('QueuedBuffer - VRI[AppListActivity]#8BLAST#8',
                  'VRI[AppListActivity]#8(BLAST Consumer)8',
                  'PendingRelease',
                  'aq:pending:com.yadea.launcher/com.yadea.launcher.function.applist.AppListActivity')
  AND (SELECT start_ts FROM trace_bounds LIMIT 1) + 6750000000 <= c.ts
  AND c.ts <= (SELECT start_ts FROM trace_bounds LIMIT 1) + 9100000000
ORDER BY c.ts LIMIT 200;

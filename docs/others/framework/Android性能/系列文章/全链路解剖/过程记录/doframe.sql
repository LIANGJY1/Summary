SELECT 'doframe_any_tid' AS tag, (s.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, s.dur, s.name, th.tid, th.name AS tname
FROM slice s
LEFT JOIN thread_track tt ON s.track_id = tt.id
LEFT JOIN thread th ON th.utid = tt.utid
WHERE s.name LIKE '%doFrame%'
  AND s.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6700000000 AND (SELECT start_ts FROM trace_bounds) + 6900000000
ORDER BY s.ts LIMIT 20;
SELECT 'vsync_slices' AS tag, (s.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, s.dur, s.name
FROM slice s
WHERE (s.name LIKE '%scheduleVsync%' OR s.name LIKE '%Choreographer%' OR s.name LIKE '%onVsync%')
  AND s.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6820000000 AND (SELECT start_ts FROM trace_bounds) + 6900000000
ORDER BY s.ts LIMIT 20;

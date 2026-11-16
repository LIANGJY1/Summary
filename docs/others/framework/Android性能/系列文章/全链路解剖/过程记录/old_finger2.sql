SELECT 'old_S_gt25ms' AS tag, (t.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, t.dur, t.state
FROM thread_state t
WHERE t.utid = 62 AND t.dur > 25000000 ORDER BY t.ts LIMIT 20;
SELECT 'old_slices_37ee8b11' AS tag, (s.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, s.dur, s.name, th.tid
FROM slice s JOIN thread_track tt ON s.track_id=tt.id JOIN thread th ON th.utid=tt.utid
WHERE s.name LIKE '%37ee8b11%' ORDER BY s.ts LIMIT 30;

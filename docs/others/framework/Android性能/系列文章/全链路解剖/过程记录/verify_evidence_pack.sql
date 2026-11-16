SELECT 'bounds' AS tag, start_ts, end_ts FROM trace_bounds;
SELECT 'launcher_S_gt40ms' AS tag, (t.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, t.dur, t.state
FROM thread_state t
WHERE t.utid = (SELECT utid FROM thread WHERE tid = 1909) AND t.dur > 40000000
  AND t.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6700000000 AND (SELECT start_ts FROM trace_bounds) + 6900000000
ORDER BY t.ts;
SELECT 'doFrame_1909' AS tag, (s.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread th ON th.utid = tt.utid
WHERE th.tid = 1909 AND s.name LIKE 'doFrame%'
  AND s.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6700000000 AND (SELECT start_ts FROM trace_bounds) + 6900000000
ORDER BY s.ts;
SELECT 'input_slices_window' AS tag, (s.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, s.dur, s.name, th.tid
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread th ON th.utid = tt.utid
WHERE th.tid IN (1909, 1489, 1488)
  AND (s.name LIKE '%0x3b3b7f02%' OR s.name LIKE '%otifyMotion%' OR s.name LIKE '%prepare%' OR s.name LIKE '%eliverInputEvent%')
  AND s.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6700000000 AND (SELECT start_ts FROM trace_bounds) + 6900000000
ORDER BY s.ts;
SELECT 'reader_1489_states' AS tag, (t.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, t.dur, t.state, t.blocked_function
FROM thread_state t
WHERE t.utid = (SELECT utid FROM thread WHERE tid = 1489)
  AND t.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6780000000 AND (SELECT start_ts FROM trace_bounds) + 6820000000
ORDER BY t.ts;
SELECT 'dispatcher_1488_states' AS tag, (t.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, t.dur, t.state, t.blocked_function
FROM thread_state t
WHERE t.utid = (SELECT utid FROM thread WHERE tid = 1488)
  AND t.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6770000000 AND (SELECT start_ts FROM trace_bounds) + 6840000000
ORDER BY t.ts;

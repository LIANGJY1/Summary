SELECT 'launcher_wake' AS tag, (t.ts - (SELECT start_ts FROM trace_bounds)) AS rel_ts, t.dur, t.state, t.waker_utid, w.name AS waker_name, w.tid AS waker_tid
FROM thread_state t LEFT JOIN thread w ON w.utid = t.waker_utid
WHERE t.utid = (SELECT utid FROM thread WHERE tid = 1909)
  AND t.ts BETWEEN (SELECT start_ts FROM trace_bounds) + 6830000000 AND (SELECT start_ts FROM trace_bounds) + 6832000000
ORDER BY t.ts;

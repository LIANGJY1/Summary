WITH win AS (
  SELECT (SELECT start_ts FROM trace_bounds) + 6787426919 AS w0,
         (SELECT start_ts FROM trace_bounds) + 6787426919 + 7951192 AS w1)
SELECT 'cpu3_overlap_trimmed' AS tag, th.tid, th.name,
       SUM(MAX(MIN(s.ts + s.dur, w.w1), 0) - MAX(s.ts, w.w0)) AS runtime_ns,
       MIN(MAX(s.ts, w.w0)) AS first_start_rel_minus_offset,
       COUNT(*) AS slices
FROM sched s JOIN thread th USING(utid), win w
WHERE s.cpu = 3 AND s.ts < w.w1 AND s.ts + s.dur > w.w0
GROUP BY th.utid ORDER BY runtime_ns DESC LIMIT 25;

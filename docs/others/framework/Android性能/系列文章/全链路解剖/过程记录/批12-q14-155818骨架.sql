SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.depth, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 661
  AND s.ts - 6073694444196 >= 6859000000 AND s.ts - 6073694444196 <= 6877000000
  AND s.depth <= 4 AND s.dur > 50000
ORDER BY s.ts LIMIT 40;

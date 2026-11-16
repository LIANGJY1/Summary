SELECT s.ts - 6073694444196 AS rel_ts, s.dur, t.tid, s.name AS slice_name, s.depth
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
JOIN process p ON t.upid = p.upid
WHERE p.pid = 661
  AND s.ts - 6073694444196 >= 6793000000
  AND s.ts - 6073694444196 <= 6950000000
  AND t.tid IN (661, 685, 746)
ORDER BY s.ts;

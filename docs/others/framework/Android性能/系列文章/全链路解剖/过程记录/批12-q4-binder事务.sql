SELECT s.ts - 6073694444196 AS rel_ts, s.dur, t.tid, t.name AS thread_name, s.name AS slice_name, s.depth
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
JOIN process p ON t.upid = p.upid
WHERE p.pid = 661 AND t.name LIKE 'binder%'
  AND s.ts - 6073694444196 >= 6830000000
  AND s.ts - 6073694444196 <= 8620000000
ORDER BY s.ts;

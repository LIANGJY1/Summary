SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name AS slice_name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.tid = 661
  AND s.ts - 6073694444196 >= 6690000000
  AND s.ts - 6073694444196 <= 6900000000
  AND (s.name LIKE 'commit%' OR s.name LIKE 'composite%' OR s.name LIKE 'onComposerHalVsync%')
ORDER BY s.ts;

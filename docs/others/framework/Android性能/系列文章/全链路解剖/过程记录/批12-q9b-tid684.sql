SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid) WHERE t.tid = 684 ORDER BY s.ts LIMIT 25;

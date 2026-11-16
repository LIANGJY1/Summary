SELECT t.tid, t.name, COUNT(*) AS n, MIN(s.ts - 6073694444196) AS first_rel, MAX(s.ts - 6073694444196) AS last_rel
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.upid = 50
GROUP BY t.tid ORDER BY n DESC;

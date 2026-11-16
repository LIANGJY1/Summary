SELECT t.tid, t.name, COUNT(*) AS n
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.upid = 49 GROUP BY t.tid ORDER BY n DESC;

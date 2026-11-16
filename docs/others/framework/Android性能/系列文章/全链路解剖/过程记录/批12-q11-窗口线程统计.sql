SELECT t.tid, t.name AS thread_name, COUNT(*) AS n
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING (utid)
WHERE t.upid = 50
  AND s.ts - 6073694444196 >= 6700000000
  AND s.ts - 6073694444196 <= 6900000000
GROUP BY t.tid ORDER BY n DESC;

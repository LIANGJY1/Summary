-- Q1: InputDispatcher tid=1488 全部 slice，窗口 6.779–6.836s（rel）
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name, s.depth
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING(utid)
WHERE t.tid = 1488 AND s.ts - 6073694444196 BETWEEN 6779000000 AND 6836000000
ORDER BY s.ts;

-- Q5: system_server 主线程 tid=756 与 binder:756_2 tid=824 的 slice（6.795–6.831s）
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name, t.tid
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid)
WHERE t.tid IN (756, 824) AND s.ts - 6073694444196 BETWEEN 6795000000 AND 6831500000
ORDER BY s.ts LIMIT 60;

SELECT '---binder-756_2-all-window---' AS marker;
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id JOIN thread t USING(utid)
WHERE t.tid = 824 AND s.ts - 6073694444196 BETWEEN 6796300000 AND 6830400000
ORDER BY s.ts LIMIT 40;

-- Q2: InputReader tid=1489 全部 slice，窗口 6.790–6.840s；以及全 trace 内 filterInputEvent / injectInputEvent slice
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING(utid)
WHERE t.tid = 1489 AND s.ts - 6073694444196 BETWEEN 6790000000 AND 6840000000
ORDER BY s.ts;

SELECT '---all-filter---' AS marker;
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name, t.tid, t.name AS tname
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING(utid)
WHERE s.name LIKE 'filterInputEvent%' AND s.ts - 6073694444196 BETWEEN 6700000000 AND 7000000000
ORDER BY s.ts;

SELECT '---inject---' AS marker;
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name, t.tid, t.name AS tname
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread t USING(utid)
WHERE (s.name LIKE '%inject%') AND s.ts - 6073694444196 BETWEEN 6700000000 AND 7000000000
ORDER BY s.ts;

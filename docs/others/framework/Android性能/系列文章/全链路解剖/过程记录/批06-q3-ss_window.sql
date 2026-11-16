-- Q3: system_server 进程内 6.7963–6.8304s 有 CPU 活动的线程（Running 总时长排序）
SELECT t.tid, t.name, SUM(ts.dur) AS run_total, COUNT(*) AS n
FROM thread_state ts
JOIN thread t USING(utid)
JOIN process p USING(upid)
WHERE p.name = 'system_server' AND ts.state = 'Running'
  AND ts.ts - 6073694444196 BETWEEN 6796300000 AND 6830330000
GROUP BY t.tid, t.name ORDER BY run_total DESC LIMIT 25;

SELECT '---disp-waker---' AS marker;
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.waker_utid, wt.name AS waker_thread, wp.name AS waker_proc
FROM thread_state ts
LEFT JOIN thread wt ON ts.waker_utid = wt.utid
LEFT JOIN process wp ON wt.upid = wp.upid
WHERE ts.utid = (SELECT utid FROM thread WHERE tid = 1488)
  AND ts.ts - 6073694444196 BETWEEN 6830000000 AND 6831000000
ORDER BY ts.ts LIMIT 10;

SELECT '---iq-counter---' AS marker;
SELECT name FROM counter_track WHERE name IN ('iq','oq','wq') LIMIT 10;

SELECT '---ss-threads-name---' AS marker;
SELECT DISTINCT t.tid, t.name FROM thread t JOIN process p USING(upid)
WHERE p.name='system_server' AND (t.name LIKE '%Filter%' OR t.name LIKE '%ccessibility%')
ORDER BY t.tid LIMIT 40;

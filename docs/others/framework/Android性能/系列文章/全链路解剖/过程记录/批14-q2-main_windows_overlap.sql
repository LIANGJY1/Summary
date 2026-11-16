-- 批14 Q2（修正版 v2）: CPU2 与主线程两个 R 窗口的重叠裁剪统计，逐线程求和
-- 窗口 1: rel 6,796,227,034 → 6,805,540,265（R 9,313,231）
SELECT s.tid AS tid, s.name AS name,
       SUM(MIN(6073694444196 + 6805540265, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6796227034)) AS clipped,
       COUNT(*) AS slices
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts < 6073694444196 + 6805540265 AND sched.ts + sched.dur > 6073694444196 + 6796227034
GROUP BY s.tid, s.name ORDER BY clipped DESC LIMIT 20;

SELECT '---window2---' AS marker;

-- 窗口 2: rel 6,812,790,111 → 6,830,079,418（R 17,289,307）
SELECT s.tid AS tid, s.name AS name,
       SUM(MIN(6073694444196 + 6830079418, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6812790111)) AS clipped,
       COUNT(*) AS slices
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts < 6073694444196 + 6830079418 AND sched.ts + sched.dur > 6073694444196 + 6812790111
GROUP BY s.tid, s.name ORDER BY clipped DESC LIMIT 20;

SELECT '---totals---' AS marker;

SELECT 'window1_all' AS k, COUNT(*) AS n, SUM(MIN(6073694444196 + 6805540265, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6796227034)) AS total
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts < 6073694444196 + 6805540265 AND sched.ts + sched.dur > 6073694444196 + 6796227034;

SELECT 'window2_all' AS k, COUNT(*) AS n, SUM(MIN(6073694444196 + 6830079418, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6812790111)) AS total
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts < 6073694444196 + 6830079418 AND sched.ts + sched.dur > 6073694444196 + 6812790111;

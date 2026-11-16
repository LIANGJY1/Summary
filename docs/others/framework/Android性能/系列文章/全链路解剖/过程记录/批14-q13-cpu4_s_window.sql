-- 批14 Q13: CPU4 在 Launcher 主线程 S 窗口（rel 6,779,836,842–6,830,764,842）的线程构成（重叠裁剪求和）
SELECT s.tid AS tid, s.name AS name,
       SUM(MIN(6073694444196 + 6830764842, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6779836842)) AS clipped,
       COUNT(*) AS slices
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 4 AND sched.ts < 6073694444196 + 6830764842 AND sched.ts + sched.dur > 6073694444196 + 6779836842
GROUP BY s.tid, s.name ORDER BY clipped DESC LIMIT 20;

SELECT '---totals---' AS marker;

SELECT COUNT(*) AS n,
       SUM(MIN(6073694444196 + 6830764842, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6779836842)) AS total
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 4 AND sched.ts < 6073694444196 + 6830764842 AND sched.ts + sched.dur > 6073694444196 + 6779836842;

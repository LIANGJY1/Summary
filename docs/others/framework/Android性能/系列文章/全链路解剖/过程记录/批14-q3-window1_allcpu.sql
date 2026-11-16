-- 批14 Q3: 窗口 1（rel 6,796,227,034–6,805,540,265）全 CPU swapper 占比 + CPU2 全明细
SELECT sched.cpu AS cpu, s.tid AS tid, s.name AS name,
       MIN(6073694444196 + 6805540265, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6796227034) AS clipped
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.ts < 6073694444196 + 6805540265 AND sched.ts + sched.dur > 6073694444196 + 6796227034
  AND s.tid = 0
GROUP BY sched.cpu, s.tid, s.name ORDER BY clipped DESC LIMIT 10;

SELECT '---cpu2 detail---' AS marker;

SELECT s.tid AS tid, s.name AS name,
       MIN(6073694444196 + 6805540265, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6796227034) AS clipped,
       COUNT(*) AS slices
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts < 6073694444196 + 6805540265 AND sched.ts + sched.dur > 6073694444196 + 6796227034
GROUP BY s.tid, s.name ORDER BY clipped DESC LIMIT 40;

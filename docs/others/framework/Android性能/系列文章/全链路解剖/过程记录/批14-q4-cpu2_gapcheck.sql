-- 批14 Q4: CPU2 在窗口 1 前后逐 slice 列表（检查 sched 事件空洞）+ 全窗口覆盖计量
SELECT sched.ts - 6073694444196 AS rel_ts, sched.dur, s.tid, s.name
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts - 6073694444196 BETWEEN 6790000000 AND 6812000000
ORDER BY sched.ts LIMIT 60;

SELECT '---cpu2 coverage 6.79-6.84s---' AS marker;

SELECT COUNT(*) AS n, SUM(sched.dur) AS total_dur
FROM sched
WHERE sched.cpu = 2 AND sched.ts - 6073694444196 BETWEEN 6790000000 AND 6840000000;

SELECT '---window bounds check: slices crossing 6796227034---' AS marker;

SELECT sched.ts - 6073694444196 AS rel_ts, sched.dur, s.tid, s.name
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts - 6073694444196 BETWEEN 6796000000 AND 6796600000
ORDER BY sched.ts LIMIT 20;

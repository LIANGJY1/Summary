-- 批14 Q5: CPU2 在 rel [6,798,500,000, 6,806,000,000] 逐 slice 列表（检查窗口 1 后半的空洞）
SELECT sched.ts - 6073694444196 AS rel_ts, sched.dur, s.tid, s.name
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 2 AND sched.ts - 6073694444196 BETWEEN 6798500000 AND 6806000000
ORDER BY sched.ts LIMIT 80;

SELECT '---sum in this range---' AS marker;

SELECT COUNT(*) AS n, SUM(sched.dur) AS total
FROM sched
WHERE sched.cpu = 2 AND sched.ts - 6073694444196 BETWEEN 6798500000 AND 6806000000;

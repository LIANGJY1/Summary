SELECT s.tid AS tid, s.name AS name,
       SUM(MIN(6073694444196 + 6830764842, sched.ts + sched.dur) - MAX(sched.ts, 6073694444196 + 6779836842)) AS clipped,
       COUNT(*) AS slices
FROM sched LEFT JOIN thread s USING(utid)
WHERE sched.cpu = 4 AND sched.ts < 6073694444196 + 6830764842 AND sched.ts + sched.dur > 6073694444196 + 6779836842
GROUP BY s.tid, s.name ORDER BY clipped DESC LIMIT 8;

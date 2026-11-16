-- 批14 Q12: Launcher 主线程 tid 1909 S 窗口（6,779,836,842-6,830,764,842）所在 CPU 与该 CPU 的 swapper/idle 占比
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.cpu
FROM thread_state ts
WHERE ts.utid = (SELECT utid FROM thread WHERE tid=1909)
  AND ts.ts - 6073694444196 BETWEEN 6779000000 AND 6831000000
ORDER BY ts.ts LIMIT 20;

SELECT '---cpu usage of that cpu during S window---' AS marker;

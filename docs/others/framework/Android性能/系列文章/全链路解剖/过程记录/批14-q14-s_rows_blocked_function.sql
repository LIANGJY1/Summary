-- 批14 Q14: 战役窗口内三个关键线程的 S 行 blocked_function 验证（S 行是否带 blocked_function）
SELECT ts.ts - 6073694444196 AS rel_ts, ts.dur, ts.state, ts.blocked_function
FROM thread_state ts
WHERE ts.utid IN ((SELECT utid FROM thread WHERE tid=1909), (SELECT utid FROM thread WHERE tid=1488), (SELECT utid FROM thread WHERE tid=1489))
  AND ts.state = 'S'
  AND ts.ts - 6073694444196 BETWEEN 6779000000 AND 6840000000
ORDER BY ts.ts LIMIT 15;

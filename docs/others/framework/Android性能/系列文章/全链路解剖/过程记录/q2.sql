-- doFrame 155812 窗口 [6842074918, 6842074918+564193) 内 tid1909 全部 slice（含 depth）
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name, s.depth
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread th USING(utid)
WHERE th.tid = 1909
  AND s.ts >= 6073694444196 + 6842074918
  AND s.ts < 6073694444196 + 6842074918 + 564193
ORDER BY s.ts;

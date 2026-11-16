-- doFrame 155803 止(6777630419) → S 起(6779836842) 窗口 tid1909 全部 slice
SELECT s.ts - 6073694444196 AS rel_ts, s.dur, s.name
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread th USING(utid)
WHERE th.tid = 1909
  AND s.ts >= 6073694444196 + 6777630419
  AND s.ts < 6073694444196 + 6779836842
ORDER BY s.ts;

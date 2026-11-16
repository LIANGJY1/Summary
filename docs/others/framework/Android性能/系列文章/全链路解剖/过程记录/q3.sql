-- 全 trace tid1909 上名为 measure/layout/draw/input/animation/traversal/commit 的 slice 计数
SELECT s.name, COUNT(*) AS cnt, MIN(s.ts - 6073694444196) AS first_rel, MAX(s.ts - 6073694444196) AS last_rel
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread th USING(utid)
WHERE th.tid = 1909
  AND s.name IN ('measure','layout','draw','input','animation','insets_animation','traversal','commit')
GROUP BY s.name;

-- doFrame 各帧窗口内子 slice 检查（tid 1909）＋ 全程 doFrame 序列
SELECT s.ts, s.dur, s.name, s.depth
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread th USING(utid)
WHERE th.tid = 1909 AND s.name LIKE '%doFrame%'
ORDER BY s.ts;

-- 全 trace tid1909 的 RV Scroll 与 scheduleVsyncLocked/onVsync 计数与首尾
SELECT s.name, COUNT(*) AS cnt
FROM slice s JOIN thread_track tt ON s.track_id = tt.id
JOIN thread th USING(utid)
WHERE th.tid = 1909 AND (s.name = 'RV Scroll' OR s.name LIKE '%scheduleVsyncLocked%' OR s.name LIKE '%onVsync%')
GROUP BY s.name;

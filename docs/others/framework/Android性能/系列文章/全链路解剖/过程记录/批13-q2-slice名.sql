-- 批13 q2: 查找 trace 中 slice 名含 virtio/drm/flush/scanout 关键词的记录
SELECT s.name, COUNT(*) AS n
FROM slice s
WHERE s.name LIKE '%virtio%' OR s.name LIKE '%drm%' OR s.name LIKE '%flush%'
   OR s.name LIKE '%scanout%'
GROUP BY s.name
ORDER BY n DESC
LIMIT 50;

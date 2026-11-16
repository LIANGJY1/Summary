-- 批13 q1: 查找 trace 中与 drm/virtio/virtio_gpu/flush/scanout 相关的 track 名
SELECT name, COUNT(*) AS n
FROM track
WHERE name LIKE '%drm%' OR name LIKE '%virtio%' OR name LIKE '%flush%'
   OR name LIKE '%scanout%' OR name LIKE '%gpu%'
GROUP BY name
ORDER BY n DESC;

SELECT ct.name AS counter_name, c.ts - 6073694444196 AS rel_ts, c.value
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name IN ('VSYNC-sf','VSYNC-app','HW_VSYNC_0')
  AND c.ts - 6073694444196 >= 6700000000
  AND c.ts - 6073694444196 <= 6900000000
ORDER BY c.ts;

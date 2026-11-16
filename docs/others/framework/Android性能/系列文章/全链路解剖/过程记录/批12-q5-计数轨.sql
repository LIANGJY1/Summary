SELECT ct.name AS counter_name, c.ts - 6073694444196 AS rel_ts, c.value
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name LIKE '%VSYNC%' OR ct.name LIKE '%VRI%' OR ct.name LIKE '%QueuedBuffer%'
ORDER BY c.ts LIMIT 40;

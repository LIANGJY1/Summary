-- Q8: iq counter 6.83–6.95s 完整序列
SELECT c.ts - 6073694444196 AS rel_ts, CAST(c.value AS INT) AS iq
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name = 'iq' AND c.ts - 6073694444196 BETWEEN 6830000000 AND 6950000000
ORDER BY c.ts;

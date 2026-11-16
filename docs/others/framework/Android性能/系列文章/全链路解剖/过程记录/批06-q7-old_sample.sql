-- Q7: 旧样本 finger.perfetto：iq counter 全部样本（相对 trace 起点）
SELECT ct.name, c.ts - 1968722243568 AS rel_ts, CAST(c.value AS INT) AS iq
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name = 'iq' AND c.ts - 1968722243568 BETWEEN 9790000000 AND 9820000000
ORDER BY c.ts;

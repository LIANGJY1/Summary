-- Q4: iq counter 样本（6.77–6.84s 窗口）与 utid 52 解析
SELECT ct.name, c.ts - 6073694444196 AS rel_ts, CAST(c.value AS INT) AS iq
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name = 'iq' AND c.ts - 6073694444196 BETWEEN 6770000000 AND 6840000000
ORDER BY c.ts;

SELECT '---utid52---' AS marker;
SELECT t.tid, t.name, p.name AS pname FROM thread t LEFT JOIN process p USING(upid) WHERE t.utid = 52;

SELECT '---iq-all-nonzero---' AS marker;
SELECT c.ts - 6073694444196 AS rel_ts, CAST(c.value AS INT) AS iq
FROM counter c JOIN counter_track ct ON c.track_id = ct.id
WHERE ct.name = 'iq' AND c.value != 0
ORDER BY c.ts LIMIT 60;

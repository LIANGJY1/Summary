SELECT p.upid, p.pid, p.name AS pname, t.utid, t.tid, t.name AS tname
FROM thread t JOIN process p USING (upid)
WHERE p.name LIKE '%surfaceflinger%' OR p.name LIKE '%composer%'
ORDER BY p.pid, t.tid;

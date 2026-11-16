SELECT 'bounds' AS tag, start_ts, end_ts FROM trace_bounds;
SELECT 'launcher_threads' AS tag, t.tid, t.utid, t.name, p.name AS pname
FROM thread t JOIN process p USING(upid) WHERE p.name LIKE '%launcher%' AND t.is_main_thread = 1;

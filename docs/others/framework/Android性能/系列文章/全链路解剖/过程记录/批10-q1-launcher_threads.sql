-- 批10 q1：com.yadea.launcher 进程全部线程（找 RenderThread tid）
SELECT t.utid, t.tid, t.name AS thread_name, p.pid
FROM thread t JOIN process p USING (upid)
WHERE p.name LIKE '%yadea.launcher%'
ORDER BY t.tid;

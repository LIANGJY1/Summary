-- 批10 q5：旧样本 finger.perfetto 的 DrawFrames 与 doFrame 配对（图03/04 数值复验）
-- ① Launcher 进程线程（找 RenderThread tid）
SELECT t.utid, t.tid, t.name AS thread_name FROM thread t
JOIN process p USING (upid) WHERE p.name LIKE '%yadea.launcher%' ORDER BY t.tid;

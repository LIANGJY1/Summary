-- 批13 q5: raw 表全部 ftrace 事件名分布（若该版本 trace_processor 支持），用于刻画采集配置面
SELECT name, COUNT(*) AS n FROM raw GROUP BY name ORDER BY n DESC LIMIT 60;

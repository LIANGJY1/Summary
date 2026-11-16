-- 批14 Q11: 全 trace sched 相关 raw 事件计数（采集事件面）
SELECT raw.name AS event, COUNT(*) AS n
FROM raw
WHERE raw.name IN ('sched_waking','sched_wakeup','sched_switch','sched_blocked_reason','sched_wake_idle_without_ipi','sched_wakeup_new')
GROUP BY raw.name
ORDER BY n DESC LIMIT 15;

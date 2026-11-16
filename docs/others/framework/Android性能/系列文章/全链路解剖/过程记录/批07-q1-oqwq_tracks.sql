-- 批07 Q1：oq/wq 计数轨存在性（最新样本 20261113-151358-vertical-1912967）
-- 目标：InputDispatcher.cpp:6163-6177 的 traceOutboundQueueLength("oq:%s")/traceWaitQueueLength("wq:%s")
--       在本 trace 中是否有对应 counter_track。
select ct.id, ct.name, count(c.id) as samples
from counter_track ct
left join counter c on c.track_id = ct.id
where ct.name like 'oq%' or ct.name like 'wq%' or ct.name like '%iq%'
group by ct.id, ct.name
order by ct.name;

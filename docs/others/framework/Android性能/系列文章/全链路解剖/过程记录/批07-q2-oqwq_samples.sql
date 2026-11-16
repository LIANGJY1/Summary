-- 批07 Q2：oq/wq 计数轨在事件 0x3b3b7f02 分发窗口（rel 6,830,4xx–6,831,2xx ns）的采样值
-- rel_ts = c.ts - (select start_ts from trace_bounds)
select (c.ts - (select start_ts from trace_bounds)) as rel_ts, ct.name, c.value as val
from counter c
join counter_track ct on ct.id = c.track_id
where (ct.name like 'oq%' or ct.name like 'wq%')
  and c.ts - (select start_ts from trace_bounds)
      between 6820000000 and 6860000000
order by c.ts, ct.name;

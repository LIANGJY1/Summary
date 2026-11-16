-- 批07 Q3：分发线程（tid 1488）在堆积排空段（rel 6,831,500,000–6,857,000,000）的
-- prepareDispatchCycleLocked slice 序列（验证 iq 1→4 堆积事件何时逐个发布）
select (s.ts - (select start_ts from trace_bounds)) as rel_ts, s.dur, s.name, s.depth
from slice s
join thread_track tt on tt.id = s.track_id
join thread th on th.utid = tt.utid
where th.tid = 1488
  and s.name like 'prepareDispatchCycleLocked%'
  and s.ts - (select start_ts from trace_bounds)
      between 6831500000 and 6857000000
order by s.ts;

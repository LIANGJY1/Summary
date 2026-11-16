-- 批07 Q6：分发线程（tid 1488）在回执处理窗口（rel 6,832,000,000–6,844,000,000）的全部 slice
-- 验证 wq:AppList 擦除采样（6,838,299,572）对应的 startDispatchCycleLocked slice 是否存在
select (s.ts - (select start_ts from trace_bounds)) as rel_ts, s.dur, s.depth, s.name
from slice s
join thread_track tt on tt.id = s.track_id
join thread th on th.utid = tt.utid
where th.tid = 1488
  and s.ts - (select start_ts from trace_bounds)
      between 6832000000 and 6844000000
order by s.ts;

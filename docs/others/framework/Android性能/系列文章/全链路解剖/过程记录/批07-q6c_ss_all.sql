-- 批07 Q6c：system_server 全部线程在 rel 6,838,100,000–6,838,500,000 的 slice（找 wq 擦除采样点的执行者）
select (s.ts - (select start_ts from trace_bounds)) as rel_ts, s.dur, s.depth, th.tid, th.name, s.name
from slice s
join thread_track tt on tt.id = s.track_id
join thread th on th.utid = tt.utid
join process p on p.upid = th.upid
where p.name = 'system_server'
  and s.ts - (select start_ts from trace_bounds)
      between 6838100000 and 6838500000
order by s.ts;

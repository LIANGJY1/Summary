-- 批07 Q6b：分发线程 tid=1488 在 rel 6,838,000,000–6,838,600,000 的全部 slice（窄窗复验）
select (s.ts - (select start_ts from trace_bounds)) as rel_ts, s.dur, s.depth, s.name
from slice s
join thread_track tt on tt.id = s.track_id
join thread th on th.utid = tt.utid
where th.tid = 1488
  and s.ts - (select start_ts from trace_bounds)
      between 6838000000 and 6838600000
order by s.ts;

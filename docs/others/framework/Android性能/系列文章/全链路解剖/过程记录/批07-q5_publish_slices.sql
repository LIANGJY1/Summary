-- 批07 Q5：分发窗口（rel 6,830,4xx–6,831,2xx）分发线程全部 slice（含嵌套层级）
-- 验证 publishMotionEvent slice 是否作为 startDispatchCycleLocked 的子 slice 存在
select (s.ts - (select start_ts from trace_bounds)) as rel_ts, s.dur, s.depth, s.name
from slice s
join thread_track tt on tt.id = s.track_id
join thread th on th.utid = tt.utid
where th.tid = 1488
  and s.ts - (select start_ts from trace_bounds)
      between 6830540000 and 6831330000
order by s.ts;

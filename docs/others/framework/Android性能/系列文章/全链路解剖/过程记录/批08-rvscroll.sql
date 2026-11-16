-- 批08 补查：RV Scroll slice 与主线程状态序列（rel 基准 start_ts=6073694444196）
select s.ts - 6073694444196 as rel_ts, s.dur, s.name, t.tid, t.utid
from slice s
join thread_track tt on s.track_id = tt.id
join thread t on tt.utid = t.utid
where t.tid = 1909
  and s.ts - 6073694444196 between 6830000000 and 6845000000
order by s.ts;

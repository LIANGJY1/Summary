-- B16-Q12 finger: 51.72ms window (abs 1978.489s-1978.5407s, rel 9.766756-9.818756s)
-- clamped main thread states inside window
with w as (select 1968722243568 + 9766756432 as w0, 1968722243568 + 9818756432 as w1)
select s.state, count(*) n, round(sum(min(s.ts+s.dur,(select w1 from w)) - max(s.ts,(select w0 from w)))/1e6,2) ms
from thread_state s, w
join thread t on s.utid=t.utid join process p on t.upid=p.upid
where p.name='com.yadea.launcher' and t.is_main_thread=1
 and s.ts < (select w1 from w) and s.ts+s.dur > (select w0 from w)
group by s.state order by ms desc;
-- delivers inside and near window
with w as (select 1968722243568 + 9766756432 as w0, 1968722243568 + 9818756432 as w1)
select round((s.ts - (select w0 from w))/1e6,2) rel_ms, s.name
from slice s, w
join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
where p.name='com.yadea.launcher' and t.is_main_thread=1 and s.name like 'deliverInputEvent%'
 and s.ts > (select w0 from w) - 20e6 and s.ts < (select w1 from w) + 5e6
order by s.ts;
-- B16-Q13 finger_h: top deliver gaps
with d as (
  select s.ts, lead(s.ts) over (order by s.ts) - s.ts gap
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name='com.yadea.launcher' and t.is_main_thread=1 and s.name like 'deliverInputEvent%')
select round(d.ts/1e6,3) ts_s, round(gap/1e6,2) gap_ms from d where gap is not null order by gap desc limit 3;

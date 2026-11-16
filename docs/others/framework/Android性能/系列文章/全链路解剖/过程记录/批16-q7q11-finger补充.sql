-- B16-Q7 Reader D blocked_function breakdown
select s.blocked_function, count(*) n, round(sum(s.dur)/1e6,1) ms
from thread_state s join thread t using(utid)
where t.name='InputReader' and s.state='D'
group by s.blocked_function order by ms desc limit 8;
-- B16-Q8 main thread D blocked_function
select s.blocked_function, count(*) n, round(sum(s.dur)/1e6,1) ms
from thread_state s join thread t using(utid) join process p on t.upid=p.upid
where p.name like '%yadea.launcher%' and t.is_main_thread=1 and s.state='D'
group by s.blocked_function order by ms desc limit 8;
-- B16-Q9 RV Scroll interval stats
with d as (
  select s.ts, lead(s.ts) over (order by s.ts) - s.ts gap
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like '%yadea.launcher%' and t.is_main_thread=1 and s.name like 'RV Scroll%')
select count(*) n, round(avg(gap)/1e6,2) mean_ms, sum(gap<=17e6) le17, sum(gap>17e6) gt17, count(*) total from d where gap is not null;
-- B16-Q10 eventTime deltas from deliverInputEvent names
with ev as (
  select cast(replace(substr(s.name, instr(s.name,'eventTimeNano=')+15), ')','') as number) et
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like '%yadea.launcher%' and t.is_main_thread=1 and s.name like 'deliverInputEvent%'),
 d as (select et, lead(et) over (order by et) - et gap from ev)
select count(*) n, round(avg(gap)/1e6,2) mean_ms, round(sum(gap<=17e6)*100.0/count(*),1) pct_le17, sum(gap is not null) total
from d where gap is not null and gap>0;
-- B16-Q11 trace span
select round((max(ts)-min(ts))/1e9,3) span_s from thread_state;

-- main thread states
select s.state, count(*) n, round(sum(s.dur)/1e6,1) ms
from thread_state s join thread t using(utid) join process p on t.upid=p.upid
where p.name like 'com.yadea.launcher' and t.is_main_thread=1 group by s.state order by ms desc;
-- main thread D blocked_function
select s.blocked_function, count(*) n, round(sum(s.dur)/1e6,1) ms
from thread_state s join thread t using(utid) join process p on t.upid=p.upid
where p.name like 'com.yadea.launcher' and t.is_main_thread=1 and s.state='D'
group by s.blocked_function order by ms desc limit 6;
-- doFrame interval histogram + mean
with f as (
  select s.ts, lead(s.ts) over (order by s.ts) - s.ts gap
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like 'com.yadea.launcher' and t.is_main_thread=1 and s.name like 'Choreographer#doFrame%')
select count(*) frames, round(avg(gap)/1e6,2) mean_gap_ms,
 sum(gap>=14e6 and gap<18e6) b14_18, sum(gap>=18e6 and gap<25e6) b18_25,
 sum(gap>=25e6 and gap<34e6) b25_34, sum(gap>=34e6) b34p from f;
-- deliverInputEvent intervals
with d as (
  select s.ts, lead(s.ts) over (order by s.ts) - s.ts gap
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like 'com.yadea.launcher' and t.is_main_thread=1 and s.name like 'deliverInputEvent%')
select count(*) n, round(avg(gap)/1e6,2) mean_gap_ms, round(min(gap)/1e6,2) min_ms,
 sum(gap<=17e6) le17, sum(gap>17e6 and gap<=34e6) b17_34, sum(gap>34e6) gt34
from d where gap is not null;
-- trace span
select round((max(ts)-min(ts))/1e9,3) span_s from thread_state;

-- B16-Q1 launcher main thread states
select s.state, count(*) n, round(sum(s.dur)/1e6,1) ms
from thread_state s join thread t using(utid) join process p on t.upid=p.upid
where p.name like '%yadea.launcher%' and t.is_main_thread=1 group by s.state order by ms desc;
-- B16-Q2 doFrame count & interval histogram
with f as (
  select s.ts, lead(s.ts) over (order by s.ts) - s.ts gap
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like '%yadea.launcher%' and t.is_main_thread=1 and s.name like 'Choreographer#doFrame%')
select count(*) frames, sum(gap is not null) intervals,
 round(avg(gap)/1e6,2) mean_gap_ms,
 sum(gap>=14e6 and gap<18e6) b14_18,
 sum(gap>=18e6 and gap<25e6) b18_25,
 sum(gap>=25e6 and gap<34e6) b25_34,
 sum(gap>=34e6) b34p
from f;
-- B16-Q3 deliverInputEvent intervals
with d as (
  select s.ts, lead(s.ts) over (order by s.ts) - s.ts gap
  from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
  where p.name like '%yadea.launcher%' and t.is_main_thread=1 and s.name like 'deliverInputEvent%'),
 o as (select gap from d where gap is not null order by gap)
select (select count(*) from o) n_deliver, (select count(*) from o)/1e6 span_s,
 round((select avg(gap) from o)/1e6,2) mean_gap_ms,
 round((select gap from o limit 1 offset (select count(*)/2 from o))/1e6,2) median_gap_ms,
 round(100.0*(select count(*) from o where gap<=17e6)/(select count(*) from o),1) pct_le17;
-- B16-Q4 slice counts on main thread
select
 sum(s.name like 'RV Scroll%') rv_scroll,
 sum(s.name='animation') anim,
 sum(s.name='draw') draw,
 sum(s.name='traversal') traversal,
 sum(s.name like '%scheduleVsyncLocked%') sched_vsync,
 sum(s.name like 'deliverInputEvent%') deliver,
 sum(s.name='input') input_stage,
 sum(s.name like 'Choreographer#doFrame%') doframe
from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
where p.name like '%yadea.launcher%' and t.is_main_thread=1;
-- B16-Q5 notifyMotion count on Reader thread
select count(*) notify_motion from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid
where t.name='InputReader' and s.name like 'notifyMotion%';
-- B16-Q6 InputReader thread states
select s.state, count(*) n, round(sum(s.dur)/1e6,1) ms
from thread_state s join thread t using(utid) where t.name='InputReader' group by s.state order by ms desc;

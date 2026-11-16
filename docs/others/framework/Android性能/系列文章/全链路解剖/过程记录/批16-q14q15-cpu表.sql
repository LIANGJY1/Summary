-- B16-Q14 all-thread Running top (finger.perfetto, full span)
select p.name pname, t.name tname, t.tid, round(sum(s.dur)/1e6,1) run_ms
from thread_state s join thread t using(utid) join process p on t.upid=p.upid
where s.state='Running' group by t.utid order by run_ms desc limit 14;
-- B16-Q15 total running capacity
select round(sum(s.dur)/1e6,1) total_running_ms from thread_state s where s.state='Running';
select round((max(ts)-min(ts))/1e9,3)*6 core_seconds from thread_state;

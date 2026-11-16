select count(*) n, round(avg(dur)/1e6,2) avg_ms, round(max(dur)/1e6,2) max_ms,
 sum(dur>16.67e6) over_budget
from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
where p.name='com.yadea.launcher' and t.is_main_thread=1 and s.name like 'Choreographer#doFrame%';

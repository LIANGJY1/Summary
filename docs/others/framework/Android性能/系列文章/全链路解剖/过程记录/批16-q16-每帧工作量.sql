-- traversal/draw slice duration stats (finger.perfetto main thread)
select s.name, count(*) n, round(avg(s.dur)/1e6,2) avg_ms, round(sum(s.dur)/1e6,1) total_ms
from slice s join thread_track tt on s.track_id=tt.id join thread t on tt.utid=t.utid join process p on t.upid=p.upid
where p.name='com.yadea.launcher' and t.is_main_thread=1 and s.name in ('traversal','draw','animation')
group by s.name;

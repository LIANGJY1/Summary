select p.name, count(distinct t.utid) threads, sum(t.is_main_thread) main_threads
from thread t join process p on t.upid=p.upid where p.name like '%yadea%' group by p.name;

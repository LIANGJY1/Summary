-- 批08 补查：Launcher 主线程（tid 1909）状态序列 窗口 6.8307–6.8340
select ts - 6073694444196 as rel_ts, dur, state, io_wait, waker_utid
from thread_state
where utid = (select utid from thread where tid = 1909)
  and ts - 6073694444196 between 6830700000 and 6834000000
order by ts;

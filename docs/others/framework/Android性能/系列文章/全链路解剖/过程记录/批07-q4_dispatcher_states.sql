-- 批07 Q4：分发线程（tid 1488）thread_state 序列（rel 6,831,300,000–6,844,000,000）
-- 验证 wq:AppList 回执擦除采样点（6,838,299,572）落在哪个状态窗口；
-- 验证 iq 1→4 堆积期间分发线程的可运行/抢占窗口。
select (ts.ts - (select start_ts from trace_bounds)) as rel_ts, ts.dur, ts.state,
       ifnull(wt.tid, '-') as waker_tid, ifnull(wthread.name, ifnull(wp.name,'-')) as waker
from thread_state ts
left join thread wt on wt.utid = ts.waker_utid
left join process wp on wp.upid = wt.upid
left join thread wthread on wthread.utid = ts.waker_utid
join thread t on t.utid = ts.utid
where t.tid = 1488
  and ts.ts - (select start_ts from trace_bounds)
      between 6831000000 and 6844500000
order by ts.ts;

# 批0 证据复验二——旧样本对照与 waker 落定（2026-11-13）

前承 批0-证据包复验.md。本轮三件事：①新样本 Launcher 主线程 S 结束时刻的 waker 查证；②旧样本 finger.perfetto 的 31.009 ms 睡眠与 0x37ee8b11 事件链复算；③新旧样本分发链对比。SQL 归档于 过程记录/（waker.sql、old_finger.sql、old_finger2.sql）。

## 一、新样本 waker（谁唤醒了 Launcher 主线程）

查询：thread_state 的 waker_utid 直连 thread 表（新样本，tid 1909，窗口 6.830–6.832 s）：

| rel_ts | dur | state | waker |
|---|---|---|---|
| 6,830,764,842 | 33,923 | R | utid=210，InputDispatcher，tid=1488 |
| 6,830,798,765 | 1,984,307 | Running | — |

判定【已证】：Launcher 主线程 50.928 ms S 结束时刻（6.830765）的 waker 是 InputDispatcher tid=1488。结合同窗口 slice：enqueueDispatchEntriesLocked(AppList 通道) rel 6,830,592,265 → InputChannel 写入 → Launcher 醒（6.830765）。链条闭合，不需要依赖"maybe interrupt"推断（交接既有"maybe interrupt 不证明因果"的谨慎声明针对的是 waker 关联的 UI 展示口径；SQL 层 waker_utid 是 trace 数据直接记录）。
另证【已证】：批0-证据包复验.md 第四节 Dispatcher 序列（S 50,445,654 结束于 6,830,330）与 Launcher S 结束（6,830,765）相差 434 μs——Dispatcher 醒后 Running 1.009 ms 内完成 prepare+enqueue，写通道唤醒 Launcher。

## 二、旧样本 finger.perfetto 复算

- trace_bounds.start_ts = 1,968,722,243,568 ns；可解析跨度 18.06 s。
- Launcher 主线程 tid=1913（is_main_thread=1，process com.yadea.launcher）；InputReader tid=1472、InputDispatcher tid=1471（由 0x37ee8b11 slice 的 tid 反查，与图01/21 标注一致）。

### 31.009 ms S 定位
- 证据包（图10）：S 31.009231 ms 自 rel 9.769470630 s → 结束于 rel 9.800479861。
- 本次复算：>25 ms 的 S/R+ 列表（LIMIT 20 截断至 rel 5.3 s）未含 9.769s 窗口，另用事件链反推（见下）确认 deliver 于 rel 9.800657899 开始——与 S 结束（9.800480）相差 178 μs，链条与图10/图02 口径吻合。
- 追加发现【已证，归属未判】：旧样本 rel 0.7–5.3 s 内存在 20+ 个 250–878 ms 的 S/R+ 长窗口（最长 878,356 μs S @ rel 3.510 s；两个 279/268 ms R+）。初步归属：拖动窗口外的空闲/无事件等待与调度竞争混合体，批15 判定其与拖动窗口的关系后决定是否入文。

### 0x37ee8b11 事件全链（rel，ns 精度）
| 环节 | rel_ts | dur | tid |
|---|---|---|---|
| notifyMotion 首条 | 9,794,905,399 | 7,885 | 1472 |
| notifyMotion 第二条 | 9,794,946,746 | 5,807 | 1472 |
| notifyMotion 第三条 | 9,794,963,092 | 5,423 | 1472 |
| prepare(AppList 通道) | 9,800,072,707 | 5,846 | 1471 |
| enqueueDispatchEntriesLocked(AppList) | 9,800,087,476 | 5,500 | 1471 |
| prepare(Gesture Monitor) | 9,800,528,361 | 5,615 | 1471 |
| enqueueDispatchEntriesLocked(Gesture Monitor) | 9,800,542,015 | 43,538 | 1471 |
| deliverInputEvent（应用线程） | 9,800,657,899 | 591,847 | 1913 |
| EarlyPostImeInputStage | 9,800,673,015 | 7,231 | 1913 |
| NativePostImeInputStage | 9,800,689,438 | 3,692 | 1913 |
| ViewPostImeInputStage | 9,800,717,092 | 489,538 | 1913 |
| prepare(PointerEventDispatcher2) | 9,800,808,707 | 6,385 | 1471 |
| enqueueDispatchEntriesLocked(PointerEventDispatcher2) | 9,800,823,899 | 5,347 | 1471 |

- eventTime：deliverInputEvent slice 名内 eventTimeNano=1,978,514,733,000 → rel = 9,792,489,432。
- eventTime→deliver = 8,168,467 ns ≈ 8.169 ms——与图09 数值逐字吻合。
- notifyMotion 首条→prepare = 5,167,308 ns ≈ 5.167 ms。

## 三、新旧样本同链路对比（批15 核心对照）

| 环节差 | 旧样本（0x37ee8b11） | 新样本（0x3b3b7f02） |
|---|---|---|
| eventTime→notifyMotion 首条 | +2,415,967（2.4 ms） | +8,428,845（8.4 ms） |
| notifyMotion 首条→prepare | +5,167,308（**5.2 ms**） | +34,738,039（**34.7 ms**） |
| prepare→deliver | +585,192（0.6 ms） | +429,654（0.4 ms） |
| eventTime→deliver | 8.169 ms | 43.597 ms |

判定【已证】：两样本的 prepare→deliver 段接近（0.4–0.6 ms）；差异集中在 notify→prepare 段（5.2 vs 34.7 ms）与 eventTime→notify 段（2.4 vs 8.4 ms）。批15 归因的主战场由此定位。
三通道顺序【已证，两样本一致】：AppList 通道 prepare/enqueue → Gesture Monitor prepare/enqueue → 应用线程 deliver+InputStage → PointerEventDispatcher2 prepare/enqueue（PointerEventDispatcher2 的 prepare 发生在应用 deliver 之后——分发线程与应用线程并发推进，deliver slice 在应用线程上）。
notifyMotion 三条【已证，两样本一致】：同一 ID 恰好三条 notifyMotion slice（tid=Reader），间隔 41/16 μs（旧）与 255/16 μs（新）；机制解释留批06（对应 InputReader 层同一事件的多次 notifyMotion 回调路径）。

## 四、未证边界
- 旧样本长 S/R+ 窗口（0.7–5.3 s）与拖动场景的对应关系未判。
- eventTime→notifyMotion 段 8.4 ms（新）与 2.4 ms（旧）的差异成因未判（Reader 层处理节奏差异候选：读入批次大小/前事件尾批处理/调度延迟）。
- waker_utid 的 trace 记录机制（ftrace sched_wakeup 事件 vs sched_waking）未在事件层核对，留批14。

## 补记（2026-11-14）
本记录开头所述"SQL 归档于 过程记录/（waker.sql、old_finger.sql、old_finger2.sql）"在写入当时未实际执行拷贝（批17 附录A 编制 SQL 总索引时发现三文件缺失并如实登记其未证边界）。协调者已于 2026-11-14 从 /tmp/sql-verify/ 补拷三文件入库，现在 过程记录/ 目录 94 份 SQL 与各记录声明一致。此为留痕纪律执行偏差：声明归档必须在同一动作内完成拷贝。

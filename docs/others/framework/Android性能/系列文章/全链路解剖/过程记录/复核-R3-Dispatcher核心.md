# 复核 R3——06/07 Dispatcher 核心两篇全量复核记录（2026-11-14）

复核者：战役协调者（用户令"进行全量复核，尤其是源码原理分析讲解，整个链路正确性"）。
方法：全文逐行读＋行号锚点机械核对（python 逐条对精树 sed 取行）＋机制链逐环对完整函数实现＋trace 数值独立重跑（trace_processor_shell，SQL 存 /tmp/review-applist/，输出抄录于下）＋算术 python 闭合。
基准：/home/liang/Project/AAOS13 精树；trace start_ts=6,073,694,444,196 ns。

## 一、06 篇复核结果：通过，零错误

### 1.1 核心机制链逐环验证（全部已证）
| 环 | 文章论断 | 精树验证 | 判定 |
|---|---|---|---|
| 1 | filter 启用时 notifyMotion 转交后不入队 | InputDispatcher.cpp:4029 shouldSendMotionToInputFilterLocked→:4047 mPolicy->filterInputEvent→返回 false 即 :4048 return（注释原文 "event was consumed by the filter"），跳过 :4075 enqueueInboundEventLocked | ✅ |
| 2 | 判定函数只看 mInputFilterEnabled | :4118-4120 函数体单行 return mInputFilterEnabled | ✅ |
| 3 | IMS 转交恒返回 false | IMS.java:3014-3027：mInputFilter 非空→:3018 转交→return false；空→recycle+return true | ✅ |
| 4 | 转交=Handler post 到主线程 | InputFilter.java:152-154 obtainMessage(MSG_INPUT_EVENT).sendToTarget()；:123-124 mH=new H(looper)；AccessibilityInputFilter.java:191 super(context.getMainLooper())；H.handleMessage MSG_INPUT_EVENT :243-250→onInputEvent | ✅ |
| 5 | 安装方唯一=无障碍 | AMS.java:2349-2356→WMS.java:7729-7730→IMS.java:815/:843；全树 grep setInputFilter( 调用方仅 AMS 一处（services 外零命中） | ✅ |
| 6 | 回注保原 id、不经 filter | injectInputEvent :4173 全函数无 shouldSendMotionToInputFilterLocked/filterInputEvent 调用；:4282 MotionEntry(motionEvent.getId(),...)；POLICY_FLAG_FILTERED 只影响 deviceId 保留(:4194-4197)与跳过 intercept(:4264-4272) | ✅ |
| 7 | 回注入队+wake | :4335-4345 while 逐条 enqueueInboundEventLocked→needWake→mLooper->wake()；MODE_ASYNC :4328-4330 不等结果 | ✅ |
| 8 | 宿主链 | InputFilter.sendInputEvent :162-178→mHost.sendInputEvent→IMS.InputFilterHost :3505/:3515→mNative.injectInputEvent :3525 | ✅ |
| 9 | iq 轨语义与采样点 | :6157-6161 ATRACE_INT("iq", mInboundQueue.size())；调用点 :982(入队)/:774(出队)/:1238(清队) 三处穷尽 | ✅ |

### 1.2 行号锚点机械核对
124 条锚点（脚本 /tmp/review-applist/check_anchors06.py），122 条直接命中；2 条初报 MISMATCH 经人工复核为区间引用正确：
- :89 STALE_EVENT_TIMEOUT 常量（10s×HwTimeoutMultiplier）与 :914 成员 mStaleEventTimeout 并存，文章"默认 10s×倍率 :89"正确；
- :4066-4071 trackListener 区间（实际调用 :4070，区间首行 :4066 为 if 条件）正确。
结论：124/124 通过。

### 1.3 trace 独立重跑（SQL 与输出）
rv_iq.sql（iq 轨 6.77–6.86s）：6771783765→0、6830307188→1、6830457303→0、6831978418→1、6832281418→2、6832668957→3、6834162765→4、6843415303→3、6853459303→2、6854512880→1、6855207957→2、6855767726→1、6856607149→0——与文章 §3.6/§4.9/§7 逐字一致。
rv_slices.sql（tid1489 6.795–6.797s）：notifyMotion(id=0x3b3b7f02) 6795838649/6577、6796093957/5692、6796110226/5231；interceptMotionBeforeQueueing 6796128072/5962；filterInputEvent 6796151188/109961——与 §4.9 表逐字一致。间隔 255308/16269 ns 复算吻合。
rv_wakers.sql（三线程 R/Running）：756 R 6796227034/9313231 waker=1489；756 R 6807249534/2707692 waker=1489；756 R 6812790111/17289307 waker=NULL；756 Running 6830079418/286693；1488 R 6830330803/35308 waker=756；1488 Running 6830366111/1009154；1909 R 6830764842/33923 waker=1488；1488 R 6831991034/6164154 waker=756——三跳 waker 链 tid756→tid1488→tid1909 全直证，与文章逐字一致。

### 1.4 算术闭合
九段和 422500+9279116+7249846+17289307+227770+23615+35308+109307+101270=34738039 ✅；里程碑差 6830576688−6795838649=34738039 ✅；二段 34468539+269500=34738039 ✅；旧样本 4196847+970461=5167308 ✅；S 窗 6779885149+50445654=6830330803 ✅；入队→LauncherR 457654 ✅。

## 二、07 篇复核结果：机制与数值全过；4 处引文瑕疵已修正

### 2.1 行号锚点机械核对
90 条锚点（check_anchors07.py），86 条直接命中；4 条初报 MISMATCH 人工复核结果：
- :3546-3554 trackGraphicsLatency：区间引用正确（实际调用 :3551，区间覆盖 TIMELINE 分支）✅
- **InputDispatcher.h:535 mAnrTracker：实际 :528 ❌ 已修正**
- **InputDispatcher.h:530-534 注释：实际 :523-527 ❌ 已修正**（引文文本本身逐字正确）
- **JNI notifyWindowUnresponsive :840-859：函数实际 :831-848，CallVoidMethod :845-846 🟡 已修正为 :831-848**
- **§11 ":3311 的 mChannel->sendMessage(&msg)"：:3311 实为 InputDispatcher.cpp publishMotionEvent 调用末行，sendMessage 本体在 InputTransport.cpp:621 🟡 已修正表述**（与本篇 §1 概念 1 的正确表述对齐）

### 2.2 grep 类论断全部复验通过
- InputTransport.cpp ATRACE 计数=12、无 #define ATRACE_TAG ✅；cutils/trace.h:86-87 缺省 ATRACE_TAG_NEVER、:50 NEVER=0 ✅
- LatencyAggregator/LatencyTracker/InputEventTimeline 三文件 ATRACE=0/0/0 ✅
- trackListener 全文件唯一调用 :4070（三重条件含 !mInputFilterEnabled）✅
- sendDispatchSignal/dispatchMotionEvent 树内零命中 ✅
- IInputConstants.aidl:26 UNMULTIPLIED_DEFAULT_DISPATCHING_TIMEOUT_MILLIS=5000 ✅（实际路径 frameworks/native/libs/input/android/os/）

### 2.3 trace 独立重跑
rv_oqwq2.sql（oq/wq 6.8305–6.8314s）：6830672995 oq:AppList→1、6830794765→0、6830811995 wq:AppList→1、6830902649 oq:GM→1、6830992726→0、6831001572 wq:GM→1、6831091649 oq:PED2→1、6831178726→0、6831186995 wq:PED2→1、6831311957 wq:GM→0——§4.5 九点＋§5.4 GM 出队点逐字一致。轨名截断形态（oq:1b4998d com.yadea.launcher/com.yadea）与 char[40] 机制一致。
算术：五段和 15577+115192+57385+47153+194347=429654 ✅；驻留 310385/7487577/12192693 ✅；夹逼上界 6830764842−6830712726=52116 ✅。

### 2.4 修正清单（本次复核改动，共 5 处编辑 4 个问题）
1. §1 概念 3：InputDispatcher.h:535→:528
2. §4.4：InputDispatcher.h:530-534→:523-527
3. §6.4：JNI :840-859→:831-848（补 CallVoidMethod :845-846）
4. §11：":3311 的 mChannel->sendMessage(&msg)"→":3293-3311 的 publishMotionEvent 调用（其内部 InputTransport.cpp:621 的 mChannel->sendMessage(&msg)）"
5. §13 E20 证据列：JNI :840-859→:831-848
性质：全部为引文行号/归属精度问题，无机制性错误；机制讲解、三态标注、数值全部通过。

## 三、复核口径声明
- 未证边界维持（不得当缺陷）：R 双窗竞争因果、S 具体等待条件、filter 内部语义、设备 build 一致性、wq 配套 restart slice 缺失候选。
- 本次复核未改动任何源码树；trace 只读查询；文章改动仅上述 5 处引文修正。
- SQL 与脚本存档：/tmp/review-applist/（rv_iq.sql、rv_slices.sql、rv_wakers.sql、rv_oqwq2.sql、check_anchors06.py、check_anchors07.py）。
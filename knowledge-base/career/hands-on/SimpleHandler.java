/**
 * 面试手写题 4/5：简化版 Handler（MessageQueue + Looper + Handler 骨架，learnframework 真题原题）
 * 讲解主线：每个线程一个 Looper（ThreadLocal）→ Looper 持 MessageQueue →
 *           Handler 往队列塞消息（按 when 排序的单链表）→ loop() 死循环取消息、用 msg.target 分发。
 * 与真实 Android 的差异（面试官追问"哪里不一样"时的答案）：
 *   1) 阻塞机制：这里 wait/notify；真实是 nativePollOnce → epoll（为了和 InputChannel 等 fd 共用一套睡眠）；
 *   2) 真实有消息屏障（target=null，动画/VSYSNC 用异步消息插队）；
 *   3) 真实有 Message 对象池 recycle 复用（防内存抖动）；
 *   4) 真实 idle 时跑 IdleHandler。
 */
public class SimpleHandler {
    static class Message {
        int what; Object obj; long when;
        Message next;          // 单链表
        Handler target;        // 分发时指回发送者
    }

    static class MessageQueue {
        private Message mMessages;
        synchronized void enqueueMessage(Message msg, long when) {
            msg.when = when;
            if (mMessages == null || msg.when < mMessages.when) {  // 插队首
                msg.next = mMessages; mMessages = msg;
                notifyAll();                                       // 叫醒可能在空等/定时等的取消息线程
                return;
            }
            Message p = mMessages;
            while (p.next != null && p.next.when <= msg.when) p = p.next; // 找插入位
            msg.next = p.next; p.next = msg;
        }
        synchronized Message next() {
            for (;;) {
                if (mMessages == null) { idle(-1); continue; }            // 无消息：无限睡
                long delay = mMessages.when - System.currentTimeMillis();
                if (delay <= 0) {
                    Message m = mMessages; mMessages = m.next; m.next = null;
                    return m;
                }
                idle(delay);                                              // 定时睡
            }
        }
        private void idle(long timeout) {
            try { if (timeout < 0) wait(); else wait(timeout); }
            catch (InterruptedException ignored) {}
        }
    }

    static class Looper {
        private static final ThreadLocal<Looper> sLooper = new ThreadLocal<>();
        final MessageQueue mQueue = new MessageQueue();
        static void prepare() {
            if (sLooper.get() != null) throw new IllegalStateException("一个线程只能有一个 Looper");
            sLooper.set(new Looper());
        }
        static Looper myLooper() { return sLooper.get(); }
        void loop() {
            for (;;) {
                Message m = mQueue.next();
                m.target.dispatchMessage(m);   // 真实 Android 分发后 recycle 进对象池
            }
        }
    }

    static class Handler {
        final void postDelayed(Runnable r, long delay) {
            enqueue(wrap(r), System.currentTimeMillis() + delay);
        }
        final void sendMessage(Message msg) {
            enqueue(msg, System.currentTimeMillis());
        }
        private void enqueue(Message msg, long when) {
            Looper looper = Looper.myLooper();
            if (looper == null) throw new IllegalStateException("子线程要先 Looper.prepare()");
            msg.target = this;
            looper.mQueue.enqueueMessage(msg, when);
        }
        private static Message wrap(Runnable r) {
            Message m = new Message(); m.obj = r; return m;
        }
        void dispatchMessage(Message m) {
            if (m.obj instanceof Runnable) ((Runnable) m.obj).run();
            else handleMessage(m);
        }
        protected void handleMessage(Message m) {}
    }

    public static void main(String[] args) throws Exception {
        Thread worker = new Thread(() -> {
            Looper.prepare();
            Handler h = new Handler() {
                @Override protected void handleMessage(Message m) {
                    System.out.println("handleMessage what=" + m.what + " obj=" + m.obj);
                }
            };
            h.sendMessage(wrapMsg(h, 42, "hello"));
            h.postDelayed(() -> System.out.println("延迟 300ms 的 Runnable 执行了"), 300);
            Looper.myLooper().loop();
        });
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(500);
        System.out.println("main 退出（worker 是守护线程被带走）");
    }
    private static Message wrapMsg(Handler h, int what, Object obj) {
        Message m = new Message(); m.what = what; m.obj = obj; return m;
    }
}

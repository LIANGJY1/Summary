import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 面试手写题 2/5：生产者-消费者（两版）
 * 版本一是限时书面必写；版本二写出关键差异即可。
 * 追问 1：为什么用 while 不用 if —— 被唤醒后条件可能又被别的线程抢走（也防虚假唤醒），要重新检查。
 * 追问 2：为什么 notifyAll —— 生产者和消费者 wait 在同一把锁上，notify 可能叫醒"同类"导致丢唤醒；
 *         BlockingQueue 用两把 Condition(notFull/notEmpty) 做到精准唤醒，这是它更优的原因。
 * 追问 3：死锁风险 —— put/take 都是先 check 后动作且持锁阻塞，天然无死锁；错在把 wait 写成 sleep。
 */
public class ProducerConsumer {
    /** 版本一：wait/notifyAll 手写有界缓冲 */
    static class BoundedBuffer {
        private final Queue<Integer> q = new ArrayDeque<>();
        private final int cap;
        BoundedBuffer(int cap) { this.cap = cap; }
        public synchronized void put(int v) throws InterruptedException {
            while (q.size() == cap) wait();   // 满：释放锁等待（锁已让出，生产者不卡消费者）
            q.offer(v);
            notifyAll();
        }
        public synchronized int take() throws InterruptedException {
            while (q.isEmpty()) wait();
            int v = q.poll();
            notifyAll();
            return v;
        }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("--- 版本一 wait/notifyAll ---");
        BoundedBuffer buf = new BoundedBuffer(3);
        Thread p = new Thread(() -> {
            try { for (int i = 1; i <= 5; i++) { buf.put(i); System.out.println("P put " + i); } }
            catch (InterruptedException ignored) {}
        });
        Thread c = new Thread(() -> {
            try { for (int i = 0; i < 5; i++) { Thread.sleep(10); System.out.println("C take " + buf.take()); } }
            catch (InterruptedException ignored) {}
        });
        p.start(); c.start(); p.join(); c.join();

        System.out.println("--- 版本二 BlockingQueue ---");
        BlockingQueue<Integer> bq = new ArrayBlockingQueue<>(3);
        new Thread(() -> { try { for (int i = 1; i <= 3; i++) bq.put(i); } catch (InterruptedException ignored) {} }).start();
        for (int i = 0; i < 3; i++) System.out.println("C take " + bq.take());
    }
}

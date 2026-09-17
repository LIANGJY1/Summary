/**
 * 面试手写题 1/5：DCL 双重检查锁单例
 * 书面目标：3 分钟内写完，并能补充回答三个追问。
 * 追问 1：为什么 volatile —— new 不是原子操作：分配内存→初始化→引用赋值，
 *         无 volatile 时可能重排为 1→3→2，另一线程在第一次判空拿到"非 null 但未初始化"的引用。
 * 追问 2：为什么要两次判空 —— 第一次避免已存在时抢锁；第二次防止等锁期间别的线程已创建。
 * 追问 3：还有哪些写法 —— 静态内部类 Holder（类加载锁天然线程安全+懒加载）、
 *         枚举（防反射/反序列化，Effective Java 推荐）。
 */
public class SingletonDCL {
    private static volatile SingletonDCL sInstance;
    private SingletonDCL() {}
    public static SingletonDCL getInstance() {
        if (sInstance == null) {                    // 第一次判空
            synchronized (SingletonDCL.class) {
                if (sInstance == null) {            // 第二次判空
                    sInstance = new SingletonDCL();
                }
            }
        }
        return sInstance;
    }
}

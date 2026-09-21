import java.util.HashMap;
import java.util.Map;

/**
 * 面试手写题 3/5：手写 LRU（面试官常说"别用 LinkedHashMap"——所以主答案必须是双向链表版）
 * 要点：HashMap 做 O(1) 索引 + 哨兵头尾双向链表做 O(1) 移动/淘汰；
 *       get 也是一次"使用"，要把节点移到尾部（最新）；淘汰 head.next（最旧）。
 * 追问：LinkedHashMap 版一行怎么写 —— accessOrder=true 时 get 也 move 到队尾，
 *       重写 removeEldestEntry(size()>capacity) 淘汰（Android 的 LruCache 内核就是这么包的）。
 */
public class LruCacheHandwritten<K, V> {
    private static class Node<K, V> {
        K key; V val; Node<K, V> prev, next;
        Node(K k, V v) { key = k; val = v; }
    }
    private final int capacity;
    private final Map<K, Node<K, V>> map = new HashMap<>();
    private final Node<K, V> head = new Node<>(null, null); // head.next = 最旧
    private final Node<K, V> tail = new Node<>(null, null); // tail.prev = 最新

    public LruCacheHandwritten(int capacity) {
        this.capacity = capacity;
        head.next = tail; tail.prev = head;
    }
    public synchronized V get(K key) {
        Node<K, V> n = map.get(key);
        if (n == null) return null;
        moveToTail(n);
        return n.val;
    }
    public synchronized void put(K key, V val) {
        Node<K, V> n = map.get(key);
        if (n != null) { n.val = val; moveToTail(n); return; }
        if (map.size() >= capacity) {
            Node<K, V> oldest = head.next;
            unlink(oldest);
            map.remove(oldest.key);
        }
        Node<K, V> fresh = new Node<>(key, val);
        map.put(key, fresh);
        addToTail(fresh);
    }
    private void unlink(Node<K, V> n) { n.prev.next = n.next; n.next.prev = n.prev; }
    private void addToTail(Node<K, V> n) { n.prev = tail.prev; n.next = tail; tail.prev.next = n; tail.prev = n; }
    private void moveToTail(Node<K, V> n) { unlink(n); addToTail(n); }

    public static void main(String[] args) {
        LruCacheHandwritten<Integer, String> c = new LruCacheHandwritten<>(2);
        c.put(1, "a"); c.put(2, "b");
        c.get(1);                    // 1 变最新，2 变最旧
        c.put(3, "c");               // 淘汰 2
        System.out.println(c.get(1) + " " + c.get(2) + " " + c.get(3)); // 期望 a null c
    }
}

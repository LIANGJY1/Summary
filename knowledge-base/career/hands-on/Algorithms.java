import java.util.Arrays;

/**
 * 面试手写题 5/5：反转链表 + 快排（车机系统岗最常抽的两道）
 */
public class Algorithms {
    static class ListNode {
        int val; ListNode next;
        ListNode(int v) { val = v; }
    }
    /** LC206 迭代三指针：prev/cur/nx，O(n) 时间 O(1) 空间 */
    static ListNode reverse(ListNode head) {
        ListNode prev = null, cur = head;
        while (cur != null) {
            ListNode nx = cur.next;
            cur.next = prev;
            prev = cur;
            cur = nx;
        }
        return prev;
    }
    /**
     * 快排（挖坑法）。面试口头补丁：轴取首元素在有序/逆序输入下退化 O(n²)，
     * 生产实现用三数取中或随机轴；这里限时书面写对流程即可。
     */
    static void quickSort(int[] a, int lo, int hi) {
        if (lo >= hi) return;
        int pivot = a[lo], i = lo, j = hi;
        while (i < j) {
            while (i < j && a[j] >= pivot) j--;
            a[i] = a[j];
            while (i < j && a[i] <= pivot) i++;
            a[j] = a[i];
        }
        a[i] = pivot;
        quickSort(a, lo, i - 1);
        quickSort(a, i + 1, hi);
    }

    public static void main(String[] args) {
        ListNode h = new ListNode(1); h.next = new ListNode(2); h.next.next = new ListNode(3);
        StringBuilder sb = new StringBuilder();
        for (ListNode c = reverse(h); c != null; c = c.next) sb.append(c.val).append("->");
        System.out.println(sb.append("null"));            // 期望 3->2->1->null
        int[] a = {5, 3, 8, 1, 9, 2};
        quickSort(a, 0, a.length - 1);
        System.out.println(Arrays.toString(a));           // 期望 [1, 2, 3, 5, 8, 9]
    }
}

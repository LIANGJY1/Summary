# 中断与下半部

> 收录平台无关的中断处理知识。文档结构和编号统一遵守 [../../WRITING-GUIDE.md](../../WRITING-GUIDE.md)。

## 册

- [07-中断与下半部.md](./07-中断与下半部.md) — 中断处理与延迟执行机制（13 题）：中断分发路径、触发方式与 irq_domain、中断共享、上半部与下半部分工、softirq 执行时机与 ksoftirqd、softirq/tasklet/workqueue 选型、中断上下文睡眠约束与自旋锁变体、线程化中断、MSI/MSI-X、中断亲和性、/proc 观测入口

## 内容边界

1. 收平台无关的内核中断机制；Android 驱动侧的设备访问与分发归 `../../01-android/11-原生层/02-驱动运行时.md`。

## 写作规范

文档结构、Q 编号、表达和质量验收统一遵守 [WRITING-GUIDE.md](../../WRITING-GUIDE.md)。

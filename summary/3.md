# Android 13 (API 33) 渲染架构全链路硬核解析

本文摒弃一切抽象比喻，严格基于 AOSP Android 13 源码（`android13-platform-release`），从 C++/Java Framework 层、Native 层、HAL 层到 Kernel 层，深入剖析整个显示图形栈的数据结构、进程间通信与渲染时序。

## 一、 核心概念与数据结构 (Terminology & Data Structures)

### 1. Surface 与 SurfaceControl
*   **Surface (`android.view.Surface` / `android::Surface`)**:
    *   **定义**：图形缓冲区的生产者接口封装。在 Native 层，它继承自 `ANativeWindow`，内部持有 `IGraphicBufferProducer`（在 A13 的 BLAST 架构中，具体实现由 `BLASTBufferQueue` 接管）。
    *   **职责**：供上层 `RenderThread` (基于 Skia/Vulkan/OpenGL) 或 Canvas 获取（`dequeueBuffer`）并写入像素数据。
*   **SurfaceControl (`android.view.SurfaceControl` / `android::SurfaceControl`)**:
    *   **定义**：`SurfaceFlinger` 中 `Layer` 对象的句柄（Handle）。
    *   **职责**：由 `system_server` (WMS) 或应用自身持有，用于通过 `SurfaceControl.Transaction` 向 SF 提交对 Layer 属性（如 Z-order、Matrix、Alpha、Crop）的修改。

### 2. GraphicBuffer
*   **定义**：跨进程共享的图形内存抽象（底层对应 `dma_buf`）。
*   **分配与生命周期**：由 `Gralloc` HAL（A13 中封装为 `GraphicBufferAllocator`）根据指定的 format (如 `RGBA_8888`) 和 usage flags (如 `GRALLOC_USAGE_HW_RENDER | GRALLOC_USAGE_HW_COMPOSER`) 从系统共享内存池分配。通过 Binder 传递文件描述符 (fd) 实现零拷贝跨进程共享。

### 3. BLASTBufferQueue (A13 核心同步机制)
*   **架构演进**：Android 11 引入，在 Android 13 中全面取代了旧的异步 `BufferQueue`。
*   **工作原理**：
    *   传统架构中，App 的 `BufferQueue::queueBuffer` 独立于 WMS 的 `SurfaceControl.Transaction` 提交，二者时序不一致会导致画面撕裂（UI 帧与窗口大小不同步）。
    *   在 BLAST 架构中，App `RenderThread` 渲染完成后的 `GraphicBuffer` 不再直接排队到 SF，而是被包装进一个 `SurfaceControl.Transaction` 中（调用 `Transaction::setBuffer`），与 WMS 的几何属性修改一起，**原子化地 (Atomically)** 提交给 `SurfaceFlinger`。
    *   对应的 SF 层实现从 `BufferQueueLayer` 变更为 `BufferStateLayer`。

### 4. SurfaceFlinger (SF)
*   **定义**：Android 的核心图形合成服务进程。
*   **线程模型**：重度依赖主线程 (`Main Thread`) 运行其 `MessageQueue`，所有核心状态的变更必须持有 `mStateLock`。
*   **核心组件**：
    *   `Scheduler`：负责根据屏幕刷新率动态调度 VSYNC 信号。
    *   `CompositionEngine`：负责隔离核心状态机与底层的渲染后端（HWC/RenderEngine）。

### 5. VSYNC (垂直同步机制)
*   **生成路径**：Display Hardware 发出硬件中断 -> Kernel DRM 驱动处理 -> 触发 HWC VSYNC 事件 -> `SurfaceFlinger` 的 `EventThread`。
*   **分发调度**：A13 的 `Scheduler` 维护了两个主要的 VSYNC 偏移量：
    *   `VSYNC-app`：通过 `DispSync` 相位偏移计算得出，通过 `Socket` 唤醒 App 进程的 `Choreographer` 触发 `doFrame`。
    *   `VSYNC-sf`：唤醒 `SurfaceFlinger` 的 `MessageQueue` 处理图层合成。

### 6. Hardware Composer (HWC) 与 RenderEngine
*   **HWC (Device Composition)**：
    *   硬件显示控制器的 HAL 抽象层。SF 将多个图层直接传递给 HWC，由显示硬件底层的 Display Controller / DPU (Display Processing Unit) 执行硬件覆盖合成（Overlay）。零 GPU 消耗，最高效。
*   **RenderEngine (Client Composition)**：
    *   当 HWC 算力受限（如图层过多，或遇到 HWC 不支持的复杂混合模式、圆角、高斯模糊）时，SF 会退回使用 `RenderEngine`。
    *   在 A13 中，默认实现为 `SkiaGLRenderEngine`（也支持 Vulkan 后端），调用 GPU 执行 `glDrawArrays` 将多个图层先合成到一张 `Target Buffer` 中，再将这单张 Buffer 提交给 HWC。

---

## 二、 全链路渲染时序：一帧的流转 (The Frame Pipeline)

以下是 Android 13 中，从 App 发起绘制到屏幕最终显示的严格调用栈与状态机流转（基于 16.6ms / 8.3ms 预算）：

### 阶段 1：App 进程渲染阶段 (Producer)
1.  **唤醒与计算**：`VSYNC-app` 唤醒 App 的 UI 线程，`Choreographer` 执行 `doFrame`（处理 Input, Animation, Measure/Layout/Draw）。
2.  **构建渲染树**：构建 RenderNode 树并同步给 `RenderThread`。
3.  **获取 Buffer**：`RenderThread` 的 `BLASTBufferQueue` 向上调用 `dequeueBuffer`。如果 Buffer 池耗尽且消费者未释放，此调用将被阻塞。
4.  **GPU 渲染**：调用 Vulkan/OpenGL ES API 提交绘制指令，等待 GPU 执行完毕并产生一个 `Sync Fence` (同步栅栏)。
5.  **Transaction 提交**：渲染完成后，`GraphicBuffer` 和对应的 `Release Fence` 被打包入 `SurfaceControl.Transaction`。
6.  **Binder IPC**：调用 `Transaction::apply`，通过 `Binder Oneway` 异步发送至 `SurfaceFlinger`。此时 Buffer 状态标记为 `QUEUED`。

### 阶段 2：SurfaceFlinger 状态更新阶段 (Consumer Latch)
1.  **唤醒**：`VSYNC-sf` 唤醒 SF 主线程的 `MessageQueue`，触发 `INVALIDATE` 消息。
2.  **合并 Transaction**：调用 `SurfaceFlinger::flushTransactionQueues()`，从队列中提取 App 和 WMS 提交的所有 `Transaction`，更新 `mDrawingState`（如 Layer 层级、Buffer 引用）。
3.  **Latch Buffer**：调用 `SurfaceFlinger::latchBuffers()`，遍历所有的 `BufferStateLayer`，调用 `acquireBuffer` 取出最新的 `GraphicBuffer`。此时 Buffer 状态转为 `ACQUIRED`。

### 阶段 3：CompositionEngine 合成阶段 (Composition)
SF 触发 `REFRESH` 消息，进入 `compositionengine::impl::CompositionEngine::present()`：
1.  **Output::prepare (可见性计算)**：
    *   重算 Layer 树，计算每个 Layer 的可见区域 (`VisibleRegion`) 和脏区域 (`DirtyRegion`)。
    *   将 Layer 的 Z-order 等属性向下同步给 HWC 侧的 `HWC2::Layer`。
2.  **Output::present (执行合成)**：
    *   **Validate**：调用 `HWC2::Display::validate()`，询问硬件是否能处理所有图层。硬件返回合成策略（`Device` 或 `Client`）。
    *   **GPU 回退合成**：如果存在被标记为 `Client` 的 Layer，SF 调用 `SkiaGLRenderEngine::drawLayers()`，将这些图层合成至一块暂存 Buffer。
    *   **硬件上屏**：调用 `HWC2::Display::present()`，将所有 `Device` 类型的 Layer 加上合成好的暂存 Buffer 一并提交给底层。

### 阶段 4：Kernel DRM 与屏幕显示 (Display)
1.  **Atomic Commit**：HWC 驱动通过 `drmModeAtomicCommit` IOCTL 将数据结构提交给 Linux Kernel DRM (Direct Rendering Manager) 模块。
2.  **Flip**：DRM 驱动控制硬件 Display Controller，在下一个 VSYNC 垂直回扫周期将显存数据翻转输出至面板 (OLED/LCD)。
3.  **Buffer 回收**：合成完成后，HWC 触发 `Release Fence`，SF 将 `GraphicBuffer` 的状态标记为 `RELEASED`，App 的 `BLASTBufferQueue` 收到信号后，该 Buffer 重新变为可用状态，循环往复。
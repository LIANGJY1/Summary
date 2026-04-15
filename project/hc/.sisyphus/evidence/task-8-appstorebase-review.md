# Task 8: AppStoreBase Review

## 内存与稳定性
- `ApkUtil` 持有大量静态 helper，包含 package / install / process / signature 相关工具，边界很宽。
- `AsyncTaskUtil` 持有全局 executor 与 task result map，生命周期是进程级的。

## 架构设计
- `IpcWrapper` 把泛型、Parcelable、Serializable 的解析都塞进一个共享 DTO，属于 shared contract 的敏感边界。
- `ApkUtil` 暴露了明显 Android 系统能力，既有复用价值也有边界泄漏风险。

## 性能优化
- 这里能看到 `HashMap<Integer,...>` / `ArrayMap` / `List` 的替代机会；热路径可以评估 `SparseArray` / `LongSparseArray`。
- `IpcWrapper` 的 parcel 读写在 list 场景下分配较多。

## IPC 与系统服务
- `IpcWrapper` 是 AppStore / OTA IPC 数据通道的基础件，必须避免在 shared 层掺入过多实现细节。

## 严重度
- P1：`IpcWrapper` 泛型反序列化脆弱。
- P2：工具类边界膨胀与分配开销。

## 关键词
- `ApkUtil`
- `IpcWrapper`
- `SparseArray`
- `LongSparseArray`
- `shared`
- `boundary`

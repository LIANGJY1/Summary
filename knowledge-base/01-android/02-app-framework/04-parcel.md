# Parcel 与序列化契约

> 应用框架 API 契约。边界：本文回答"Parcelable/Serializable 选型与 Parcel 读写契约"。Q 序列即结构，供 atlas 同源直读。

**Q1: Java 对象在 Android 进程间传递时，`Parcelable` 与 `Serializable` 应怎样选择？**

Android 组件间通过 Intent 或 Binder 传输对象时，优先使用符合平台契约的 `Parcelable`；`Serializable` 更简单但通常有更高的反射与编码开销。两者都要控制载荷大小：Binder 事务缓冲区有限，大对象应传 URI、数据库键或文件描述符等间接引用。

`Parcelable` 的写入顺序必须与读取顺序一致，嵌套对象与文件描述符也要遵守相应契约；自定义 `Serializable` 类型要考虑版本演进和 `serialVersionUID`。序列化格式不应被当成长期稳定的持久化协议，存储格式需要明确的版本与兼容策略。

Parcelable 通常更适合 Android 进程间传递，是因为它让类型按字段顺序直接读写 Parcel，避免 Java 原生序列化的通用对象图编码与反射成本；实际成本仍取决于载荷结构和读写实现，选择它不代表可以传送无限大的对象。

**Q2: Parcelable 对象如何通过 Parcel 写入并恢复？**

实现 Parcelable 时，`writeToParcel()` 按固定顺序写出字段，`CREATOR` 在构造对象时按完全相同的顺序读取；顺序或类型不匹配会造成字段错乱或反序列化失败。`describeContents()` 通常返回 0，包含文件描述符时需正确报告相应标记。

`Parcel` 是 Binder/组件传递使用的暂存序列化容器，不是供应用长期保存的稳定文件格式；它的内部编码不应跨版本或跨平台当成持久化协议。

# Parcel 与序列化契约

> 应用框架 API 契约。边界：本文回答"Parcelable/Serializable 选型与 Parcel 读写契约"。Q 序列即结构，供 atlas 同源直读。

**Q1: Java 对象在 Android 进程间传递时，`Parcelable` 与 `Serializable` 应怎样选择？**

选择取决于调用场景、实现成本和载荷大小：

1. **Android 组件或 Binder IPC：**通常优先使用 `Parcelable`。类型按明确字段顺序写入 Parcel，避免 Java 原生序列化的通用对象图编码与反射成本。实际开销仍取决于载荷结构和读写实现。
2. **实现简单的小型数据**：`Serializable` 使用方便，但通常有更高的反射和编码开销。自定义类型还要管理 `serialVersionUID` 与兼容策略。
3. **较大载荷**：两种格式都不能绕过 Binder 事务缓冲区限制。传 URI、数据库键或文件描述符等间接引用，让接收方按需读取数据。
4. **长期持久化**：不要把 Parcel 或默认 Java 序列化格式直接当成稳定存储协议。持久化数据应定义格式版本与迁移策略。

**Q2: Parcelable 对象如何通过 Parcel 写入并恢复？**

实现与恢复遵循一份固定字段契约：

1. **写入**：`writeToParcel()` 按约定顺序和类型写出每个字段。
2. **恢复**：`Parcelable.Creator` 的 `createFromParcel()` 按完全相同的顺序和类型读取字段并构造对象。顺序或类型不匹配会造成字段错乱或反序列化失败。
3. **描述特殊内容：**`describeContents()` 通常返回 `0`。对象携带文件描述符时，应按契约返回对应标记。
4. **限定用途**：`Parcel` 是 Binder 或组件传递使用的暂存序列化容器，不是供应用长期保存的稳定文件格式。不要把其内部编码跨版本或跨平台当成持久化协议。

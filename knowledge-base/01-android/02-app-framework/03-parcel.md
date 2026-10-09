# Parcel 与序列化契约

> 应用框架 API 契约。边界：本文回答"Parcelable/Serializable 选型与 Parcel 读写契约"。Q 序列即结构，供 atlas 同源直读。

**Q1: [learning] Java 对象在 Android 进程间传递时，Parcelable 与 Serializable 应怎样选择？**

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

**Q3: [learning] 什么场景需要把对象序列化？Android 有哪几类序列化方案？**

序列化把内存中的对象转换成可存储或可传输的形式，反序列化再把它还原成对象；凡是“对象要离开当前进程内存”的场合都需要它。典型场景有三类：

1. **进程内组件传递与跨进程通信**：通过 Intent 携带 extras、经 Binder 传递参数与返回值，接收方拿到的是重组后的新对象。
2. **持久化**：把对象状态写入磁盘或数据库，进程结束、设备重启后仍可恢复。
3. **网络传输与远程调用**：把对象编码后发给其他客户端或服务端。

方案按机制分三类：`Serializable` 是 Java 标准序列化，通用但开销较高；`Parcelable` 是 Android 定制方案，按字段顺序写入 Parcel 缓冲区，服务 Binder 与组件传递；JSON、XML、Protobuf 等格式面向文本可读性或跨语言互操作，多用于持久化与网络。日常选型的基线是：组件与 Binder 传参优先 `Parcelable`，持久化与网络选带版本管理能力的格式，小体量数据用 `Serializable` 换实现便利。

**Q4: [learning] Serializable 是一个没有任何成员的标记接口，对象的序列化与反序列化实际由谁完成？**

`Serializable` 只声明“这个类的对象允许被序列化”，真正的编码与解码由 Java 序列化基础设施完成：`ObjectOutputStream.writeObject()` 通过反射读取对象的类元数据与字段值写成字节流，`ObjectInputStream.readObject()` 按同一份流描述符还原对象。没有实现该接口的类会被 `writeObject()` 直接抛出 `NotSerializableException`。

最小用法如下，`readObject()` 返回 `Object`，需要按实际类型强转：

```java
class Student implements Serializable {
    private static final long serialVersionUID = 1L;
    String name;
}

try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("student.bin"))) {
    oos.writeObject(new Student());
}
try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream("student.bin"))) {
    Student s = (Student) ois.readObject();
}
```

序列化是通用对象图编码：流里携带类描述与字段元数据，遍历可达对象逐个写出。这正是它在 Android 上比 `Parcelable` 慢的根源——反射查找与元数据编码对每个类、每个对象都要发生一遍，而 Parcelable 的读写是按既定字段顺序的直线存取。

**Q5: [learning] 使用 Serializable 时，不显式声明 serialVersionUID 为什么可能导致类变更后反序列化失败？**

反序列化前，运行时会比对“写入流的类版本号”与“本地类派生出的版本号”，不一致直接抛 `InvalidClassException`。若类没有显式声明 serialVersionUID，序列化与反序列化两侧各自按当时的类定义（类名、接口、字段、方法签名等结构信息）派生一个默认版本号：类结构一变，派生值就可能改变，新旧两侧比对失败，曾经成功写出的数据再也读不回来。

显式声明 `private static final long serialVersionUID = 1L;` 把版本号固定下来：两侧都用声明值比对，字段增删走序列化规范定义的兼容规则（新增字段反序列化时取默认值，删除字段的数据被忽略），跨版本读取因此可控。所以显式声明的意义不是“让序列化成功”，而是把版本演化策略从“结构派生、不可控”换成“自行声明、可控”。

**Q6: [learning] 为什么静态字段和 transient 字段不会随 Serializable 序列化？**

序列化的对象是对象状态，而静态字段属于类、所有实例共享一份，把它写进某个对象的流里既无意义也会在还原时产生归属歧义，因此序列化运行时只处理实例字段。`transient` 是显式的排除声明：被它标记的字段在序列化时被跳过，反序列化后取类型默认值，适合承载缓存、连接句柄这类不能或不必跨持久化的状态。

顺带澄清一个常见误解：serialVersionUID 虽然按静态字段的形式声明，但它本身并没有被“序列化进对象数据”——运行时只是把它的值作为流描述符里的类版本号写入；未显式声明时改用按类定义派生的值，类结构变化会连带改变派生结果。

**Q7: [learning] Externalizable 与 Serializable 有什么差别？完全自控的序列化格式要付出什么？**

`Externalizable` 继承自 `Serializable`，把“由基础设施反射编码”换成“由类自己编码”：实现类必须提供 `writeExternal()` 与 `readExternal()` 两个方法，自行决定哪些字段按什么格式写出与读回，并且必须带一个 public 无参构造器——反序列化时运行时先调用它构造空对象，再由 `readExternal()` 填充状态。

代价与收益都来自这份自控：可以精确裁剪字段与格式，省掉反射与元数据开销；但读写顺序、类型与版本兼容全部由自己维护，出错时不会有基础设施兜底。除非有明确的性能或自定义格式诉求，日常代码默认用 `Serializable` 加 `transient` 排除即可，`Externalizable` 更多是理解序列化机制分层的一把钥匙。

**Q8: [learning] Parcel 底层是什么形态的容器？writeInt 这类调用到 native 层经历了什么路径？**

Parcel 本质是一段顺序字节缓冲区：Java 层的 `writeInt()`、`writeString()` 等方法把值按类型编码后依次写入缓冲区，读取侧按相同顺序解码。`Parcel.java` 的写入方法最终落到 native 方法（如 `nativeWriteInt()`），在 C++ 层把值写进底层 Parcel 对象管理的内存缓冲区；读取路径对称，由 native 层直接从缓冲区取字节再封装回 Java 数据结构。直接操作内存缓冲区、不经过中间格式，是 Parcel 编解码开销低的机制基础。

它能承载的内容包括基本类型、`Parcelable` 对象、`IBinder` 引用以及由这些组成的容器与 Bundle。缓冲区里的数据由 Binder 事务投递到接收进程的映射缓冲区后，接收方拿到的是另一段内容相同的缓冲区，再走同样的 native 读取路径还原——这也是 Parcel 只适合暂存传输、不适合当持久化格式的机制侧原因：它的编码绑定当前平台的内存布局与版本实现。

**Q9: [learning] Serializable 提供了哪些私有定制钩子？单例对象反序列化为什么会变成两份，readResolve 怎样修复？**

序列化基础设施在默认反射编码之外预留了一组按方法名约定的钩子：类声明 private void writeObject(ObjectOutputStream) 与 private void readObject(ObjectInputStream) 时，ObjectOutputStream/ObjectInputStream 会在默认读写过程中回调它们，用于字段加密、瞬态字段重建这类字段级定制；声明 readResolve() 与 writeReplace() 则可以在对象还原或写出时整体替换实例。

readResolve 的经典用途是保护单例。默认反序列化不经过构造器，而是按流中数据直接在内存重建一份实例：单例类即使构造器私有，反序列化也会得到第二个实例，单例契约被绕过。声明 private Object readResolve() { return getInstance(); } 后，readObject 返回前会用该方法的结果替换新造的实例，调用方始终拿回同一个对象。

1. **钩子必须声明为 private**：基础设施按约定反射查找这些方法，private 防止子类意外继承或覆盖这套定制。
2. **枚举单例无需修补**：枚举实例的唯一性由语言层面保证，反序列化不会产生新实例。

选择规则：字段级定制用 writeObject/readObject，实例级替换（单例、对象规范化）用 readResolve；定制越深与字段结构耦合越紧，能靠 transient 加显式重建解决的就不要手写完整读写。

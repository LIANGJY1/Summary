# SIR-6611 · 热点已连接设备列表两个设备名称显示一样

- **提交**：`4b6a4a1f` | 2026-08-27 | dufan | Setting | bugfix
- **缺陷库**：等级 B · 频次 高概率-40%~80% · 状态 关闭 · 域 车控车设

## 问题
设置里热点"已连接设备"列表中，两台不同设备显示的名称完全一样。

## 根因分析
`HotspotDialogFragment.handleConnectedClients` 解析每个 `TetheredClient` 的主机名：遍历 `client.addresses`，若 `info.hostname` 非空则取为设备名。原代码把 `var name = ""` 声明在 **for 循环外**且带初值，循环体内只有"解析到 hostname"这一个赋值点。当某台设备的 `addresses` 中没有非空 `hostname`（DHCP 未上报主机名）时，`name` 保持上一次循环残留的值，于是这台设备顶替显示了前一台设备的名字，列表出现重名。缺陷库概括为"变量未重置 → 重置变量"。修复把 `name = ""` 移入每轮循环开头。（同提交对 `BluetoothUtil` 中 `DeviceConnectManager` 的 `mCarPlayDeviceManager` 等三个属性访问改为 getter 调用 `getCarPlayDeviceManager()` 等，属于访问方式调整，与重名无直接关系。）

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt、application/Setting/src/main/java/com/yadea/setting/utils/BluetoothUtil.kt
```diff
--- application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/HotspotDialogFragment.kt
-        var name = ""
         var address: String
         for (client in clients) {
+            name = ""
             address = client.macAddress.toString()
```

## 为什么能修复
每轮循环开始时 `name` 归位为空串，解析不到主机名的设备显示空名（而非复制上一个设备的名字），列表重名消失。改动一行，无副作用；顺带把 `var name = ""` 的循环外初始化去掉，消除"声明即初始化但首用前覆盖"的误导。

## 复盘与经验
- 循环内使用的临时变量必须在每轮迭代开始处初始化；声明在循环外的旧写法（Java 遗风）天然携带跨迭代状态，是"脏值传染"类 bug 的高发模式。
- "缺数据时沿用上一次的值"是最隐蔽的展示错误——不是崩溃也不抛异常，只有把两台设备放一起对比才能发现。
- Kotlin 中将 Java 风格属性访问改为显式 getter（如 `getCarPlayDeviceManager()`）可保证每次调用都重新求值，避免持有过期实例，属顺手加固。

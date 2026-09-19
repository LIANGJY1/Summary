# SIR-8671 · [偶发] 移除设备后列表出现两个相同名称设备
- **提交**：`46848e74` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 低概率-10%~40% · 状态 待测试验证 · 域 车控车设

## 问题
偶发：移除蓝牙设备后，设备列表中同一设备出现两个相同名称的条目。

## 根因分析
`BluetoothFragment` 组装"可配对手机列表"时，直接遍历 `availableDevices`（蓝牙扫描/缓存数据）把非已配对的手机设备加入 `mPhoneAvailableDevices`。缓存中偶发存在**同 MAC 地址的重复实例**（移除设备后重新被扫描发现、或广播与缓存刷新竞态写入），原代码没有任何去重，同一地址被加入两次，列表即出现两个同名条目。缺陷库 rc"蓝牙缓存数据出现重复数据"、提交 `[why] 缓存数据问题未过滤重复蓝牙地址` 均指向此。

## 关键代码修改
改动文件：BluetoothFragment.kt（+3）
```diff
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/diologfragment/BluetoothFragment.kt
             if (mWxBtManager.isEnable && mBindingHeader.sw.isChecked) {
+                // 缓存中可能存在同 MAC 的重复实例，此处按地址去重，避免可配对列表出现重复项
+                val seenAddress = HashSet<String>()
                 availableDevices.forEach {
                     if (it.deviceType == BluetoothDeviceType.CELL_PHONE  && !it.name.contains(HEADSET_NAME_SUFFIX)) {
+                        if (!seenAddress.add(it.address)) return@forEach
                         log("availableDevices: $it--${availableDevices.size}")
                         if (it.address !in pairedAddresses) {
                             mPhoneAvailableDevices.add(
```

## 为什么能修复
在列表组装入口用 `HashSet<String>.add()` 的返回值做"首次可见"判定：`add` 返回 false 说明该 MAC 已出现过，`return@forEach` 跳过重复实例。由于蓝牙地址（MAC）是设备唯一键，按地址去重后同设备只剩一个条目，显示层重复被消除。这是展示层防御——缓存源头的重复写入（竞态）仍在，但 UI 不再受害。副作用：不同 `deviceType` 或名称后缀的过滤逻辑不受影响（去重仅在类型过滤之后、入列之前生效）；若缓存中两实例属性不同（如一个带名称一个空），取先到者，属可接受取舍。

## 复盘与经验
- 蓝牙扫描缓存天然可能含同 MAC 重复项（异步发现与增删竞态），凡是从扫描/缓存数据构建 UI 列表的地方都应按地址去重，不能信任数据源唯一性。
- `HashSet.add` 返回值是 Kotlin/Java 中最简洁的"去重 + 首次判定"写法，三行修复消除一类偶现显示缺陷。
- 偶现列表重复类问题，先问"数据什么时候可能写入两次"（移除后重扫、双回调、缓存与广播竞态），再决定修源头还是修展示——本例选择展示层防御，性价比高。

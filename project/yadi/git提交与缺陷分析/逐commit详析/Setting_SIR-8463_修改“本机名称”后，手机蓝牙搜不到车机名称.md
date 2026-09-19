# SIR-8463 · 修改"本机名称"后手机搜不到车机蓝牙，无法连接

- **提交**：`64b2d26f` | 2026-09-18 | sgh | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 解决方案 · 域 车控车设

## 问题
修改车机"本机名称"后（典型伴随重启/服务重载），手机蓝牙搜索不到车机的蓝牙名称，导致无法发起配对连接。

## 根因分析
蓝牙"被手机搜到"依赖适配器处于可发现（discoverable）模式，它与"蓝牙开关已开"（isEnable）是两个独立状态。此前车机侧只在进入蓝牙设置界面等场景调用 `setVisible(true)`，蓝牙服务初始化时从不恢复可见性；设备重启或改名导致扫描模式回落为不可见后，车机虽然蓝牙开着，手机却搜不到。缺陷库根因"重启设备蓝牙开关开时未设置可见"即指此：可见状态没有被当作持久属性在服务启动时兜底恢复。注意解决方案字段写的是"进入界面蓝牙开时设置可见"，与 diff 不符——diff 实际把兜底放在了 `SettingVehicleService` 服务初始化（与 commit message "[how]服务起来蓝牙开关开设置可见"一致），以 diff 为准。

## 关键代码修改
改动文件：application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt（+32）
```diff
--- application/Setting/src/main/java/com/yadea/setting/init/SettingVehicleService.kt
@@ 初始化尾部
         initSeatSdk()
+        initBluetoothDiscoverable()
+    }
+
+    /**
+     * 初始化蓝牙可发现状态
+     *
+     * 如果蓝牙开，就设置可见
+     */
+    private fun initBluetoothDiscoverable() {
+        ThreadUtils.executeByIoWithDelay(object : ThreadUtils.Task<Any>() {
+            override fun doInBackground(): Any? {
+                try {
+                    if (BluetoothUtil.mWxBtManager.isEnable && !BluetoothUtil.mWxBtManager.isVisible()) {
+                        BluetoothUtil.mWxBtManager.setVisible(true)
+                        LogUtils.e(TAG, "initBluetoothDiscoverable set bt visible")
+                    }
+                } catch (e: Exception) {
+                    LogUtils.e(TAG, "initBluetoothDiscoverable failed: $e")
+                }
+                return null
+            }
+            // onSuccess/onCancel/onFail 空实现
+        }, 1, TimeUnit.SECONDS)
     }
```

## 为什么能修复
服务启动后延迟 1 秒（等蓝牙协议栈就绪）在 IO 线程检查：只要蓝牙开关开启且当前不可见，就 `setVisible(true)`，把"可见"恢复为蓝牙开启后的默认状态；整段包了 try-catch，蓝牙服务未就绪抛异常也不会影响 Setting 服务其余初始化。手机端从此在任意时刻都能扫到车机，改名/重启不再破坏可发现性。副作用很小：车机将常态化保持可发现（对车机产品通常是有意为之），未监听后续用户主动关闭可见的操作。

## 复盘与经验
- 蓝牙开关、可见（discoverable）、可连接（connectable）是三个独立状态，任何"开机自恢复"清单必须逐项覆盖，只恢复开关不恢复可见是车机蓝牙的常见翻车点。
- 依赖"用户进入某界面才补状态"的隐式恢复路径不可靠，全局型设备状态应在常驻服务启动时统一兜底；异步依赖外部服务就绪时用小延迟 + try-catch 做防御。
- 车机蓝牙这类"被连接方"的可用性验收要覆盖重启、改名、开关循环等状态迁移组合，而不是只在设置界面里验证一次。

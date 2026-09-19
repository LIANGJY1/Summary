# SIR-6430/SIR-6431 · HUD 上 TBT 导航信息与蓝牙电话信息重叠
- **提交**：`cee35e9c` | 2026-08-31 | caohongliang | BTPhone | bugfix（cherry-pick 自 a8491113）
- **缺陷库**：SIR-6430 等级 C · 必现-80%~100% · 关闭 · 蓝牙电话；SIR-6431 同（根因/方案同 6431）

## 问题
HUD 详细模式下导航已开启，挂 D 档后拨打蓝牙电话，HUD 上 TBT（转向指引）信息与电话信息叠在一起显示。

## 根因分析
HUD 的信息分屏/互斥由 L2A（Launcher-to-Cluster 通信，经 `com.yadea.apf.ivicommsdk.IviCommManager`）消息驱动：仪表需要收到"蓝牙电话占用 HUD 某区域"的消息，才会把 TBT 信息让位或收起。蓝牙电话进程此前没有初始化 L2A 通道，`IviCommManager` 未 init 时发送全部失败——HUD 侧从未收到电话信息占用通知，于是照常渲染 TBT，电话信息再叠加上来，两者重叠。单据根因"L2A 服务未初始化，消息发送失败"即指此；挂 D 档/开导航只是让 TBT 出现在 HUD 的触发条件，并非根因本身。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/BtPhoneApp.java
```diff
--- a/application/BTPhone/src/main/java/com/yadea/btphone/BtPhoneApp.java
@@ Application 初始化
         RecentRepository.init(this);
         ContactRepository.init(this);
         FavoritesRepository.init(this);
+        initL2A();
...
+    private void initL2A() {
+        IviCommManager.getInstance().init(this, b -> {
+            if (!b) {
+                LogUtils.d(TAG, "L2A init failed, repeat init");
+                initL2A();
+            } else {
+                LogUtils.d(TAG, "L2A init success");
+            }
+        });
+    }
```

## 为什么能修复
蓝牙电话 Application 启动即初始化 L2A 通道，后续通话事件的消息得以真正送达仪表，HUD 按协议让位/分屏，TBT 与电话信息不再叠加。失败重试（init 回调 false 时递归重试）吸收了通信服务晚于应用启动的时序问题。隐患：递归重试没有次数上限与退避，若 L2A 服务长期不可用会持续空转打日志；建议后续加计数上限。本提交 diff 仅 13 行、与单据描述完全对应，属"补初始化"型小修。

## 复盘与经验
- 新增跨进程/跨域通信依赖（如 L2A）时，必须在消费方（本例 BTPhone）Application 阶段纳入初始化清单，靠调用点隐式初始化必然漏。
- 异步 init 回调失败要有重试策略，车机上服务启动顺序不受应用控制，"init 一次就认为成功"是幻觉。
- "HUD 显示重叠"这类仪表侧症状，排查第一步是抓仪表收到的消息流：消息没到（本例）与消息到了但仪表处理错是两类完全不同的修复路径。

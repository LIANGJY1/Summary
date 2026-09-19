# SIR-7354 · 迎宾音效开关回弹（车控信号名大小写写错）

- **提交**：`944a9a03` | 2026-09-09 | sgh | Setting | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
控制-模式-迎宾模式页面，迎宾音效开关点击后立即回弹（状态未真正写入车辆，UI 被信号回刷回原值）。

## 根因分析
`component/Carlib` 的 `CarPropertyIds.kt`（object，集中定义车控信号名字符串常量）中，迎宾相关信号被写成了全大写：`WELCOME_MODE_SWITCH = "WELCOME_MODE_SWITCH"`、`WELCOME_SOUND_SWITCH = "WELCOME_SOUND_SWITCH"`、`TURN_BSD_SW = "TURN_BSD_SW"`。而车辆信号库里实际注册的名字是驼峰式 `Welcome_Mode_Switch`/`Welcome_Sound_Switch`/`Turn_BSD_SW`。车控库按信号名查找属性时因大小写不匹配查不到（即缺陷库所记"新协议接口未投入使用"），set 写不进去、get 也读不到真实状态，开关只能维持本地临时态，随后被真实信号回刷，表现为回弹。

## 关键代码修改
改动文件：component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt（1 文件 +3/-3）
```diff
--- component/Carlib/src/main/java/com/neusoft/libcar/CarPropertyIds.kt
@@ 信号名常量定义
-    const val TURN_BSD_SW= "TURN_BSD_SW"
+    const val TURN_BSD_SW= "Turn_BSD_SW"
@@
-    const val WELCOME_MODE_SWITCH_L2A= "WELCOME_MODE_SWITCH"
+    const val WELCOME_MODE_SWITCH_L2A= "Welcome_Mode_Switch"
     //迎宾音效开关
-    const val WELCOME_SOUND_SWITCH= "WELCOME_SOUND_SWITCH"
+    const val WELCOME_SOUND_SWITCH= "Welcome_Sound_Switch"
```

## 为什么能修复
常量值改成与信号库一致的真实信号名后，Setting 页面经 Carlib 对该属性的读写能命中真实信号，开关状态真正写入 CCU 并回读确认，不再回弹。改动仅是字符串字面量，不影响其他引用该常量的编译；隐患是如果同一信号在其他模块还有硬编码的旧名字副本，会再次出现不一致，需要靠常量统一收敛。

## 复盘与经验
- 车控信号名是对外协议契约，大小写敏感；从文档抄写信号名时必须逐字符核对（本例三个信号连错误方式都相同，说明是按错误命名习惯批量手写）。
- 开关"回弹"类 bug 的标准排查链：点击 set → 信号是否写入 → 回读是否变化 → UI 是否被回刷；任一环断掉都表现为回弹，先查信号名/ID 再查逻辑。
- 信号名应只在 Carlib 常量类定义一次并供全局引用，禁止各模块复制字符串，避免多处拼写漂移。

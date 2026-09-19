# 无单号 · 优化加热图标

- **提交**：`ec29cecb` | 2026-09-03 | sgh | Setting | feature（内容实为缺陷修复：图标资源错配）
- **关联单**：无

## 问题现象
车控页把手加热与座椅加热的"关闭态"图标互相调换：把手加热关闭时显示座椅关闭图（`heat_seat_close`），座椅加热关闭时显示把手关闭图（`heat_hand_close`），用户感知为图标错乱。

## 根因分析
初始化把手/座椅加热控件的两处 `setupHeaterControl(...)` 调用，第 4 个参数（fallback 关闭图）传反了——把手控制传了 `heat_seat_close`、座椅控制传了 `heat_hand_close`，与各自 `levelIcons` 数组（hand_icons/seat_icons）不匹配。这与 `e0903b2f` 顺带修的 `seat_lock_on/off_bg` 同属"对称控件复制粘贴后资源引用未核对"。

## 关键代码修改
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/VehicleControlFragment.kt
@@ -241,7 +241,7 @@
                         R.drawable.heat_hand_second,
                         R.drawable.heat_hand_third
                     ),
-                    R.drawable.heat_seat_close,
+                    R.drawable.heat_hand_close,
                     getString(R.string.handle_heating_exception)
                 )
```
```diff
@@ -266,7 +266,7 @@
                         R.drawable.heat_seat_second,
                         R.drawable.heat_seat_third
                     ),
-                    R.drawable.heat_hand_close,
+                    R.drawable.heat_seat_close,
                     getString(R.string.seat_heating_exception)
                 )
```

## 为什么能修复
把手的 fallback 图换回 `heat_hand_close`、座椅换回 `heat_seat_close`，资源族与控件一一对应，关闭态/异常态显示恢复正确。

## 复盘与经验
- 顺带清理了两处每次状态刷新都打印的 disabled 调试日志，并修正 `voice_wake_up_hint` 字符串中未转义的英文引号（`\"`），属于低成本卫生改造。
- 对称成对的 UI 配置（把手/座椅、主驾/副驾）是资源错配重灾区：把图标组收敛为 `HeaterIcons(seat=..., hand=...)` 数据类集中声明，可让这类错误在定义处即可视核对。
- 该类 bug 无逻辑难度、纯靠肉眼，code review 时对"参数顺序相同的长参数列表"应逐参数比对资源前缀。

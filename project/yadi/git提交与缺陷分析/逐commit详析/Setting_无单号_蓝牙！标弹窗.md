# 无单号 [SRS_BT_LinkSetting_001] 蓝牙！标弹窗
- **提交**：`13355fbc` | 2026-08-24 | sgh | Setting | feature
- **关联单**：SRS_BT_LinkSetting_001

## 需求/目标
连接页"手机蓝牙"条目标题旁新增"!"说明图标，点击弹出 SentinelDialogSmall 介绍手机蓝牙的能力边界（支持的功能、与耳机蓝牙独立、单连接、5 个配对上限、默认设备回连）。

## 实现结构
- `layout_connect_item.xml`：通用连接条目布局新增 `iv_info` ImageView（24dp、默认 gone，约束在标题右侧），该布局是蓝牙/WLAN/热点多条目共用的模板。
- `ConnectFragment.kt`：蓝牙条目 `ivInfo.visibility = VISIBLE` + `setOnFastClickListener` 弹 SentinelDialogSmall（标题/正文来自 strings）。
- strings 中英各加 2 条（bluetooth_info_title/content，正文为多行 `\n` 富文本）。
- 无信号/存储改动，纯 UI 说明入口。

## 关键代码
```kotlin
// application/Setting/src/main/java/com/yadea/setting/ui/fragment/ConnectFragment.kt
+        mBinding.layoutBt.ivInfo.visibility = View.VISIBLE
+        mBinding.layoutBt.ivInfo.setOnFastClickListener {
+            SentinelDialogSmall(
+                getString(R.string.bluetooth_info_title),
+                getString(R.string.bluetooth_info_content)
+            ).show(childFragmentManager, "BluetoothInfoDialog")
+        }
```
利用共享布局 `layout_connect_item` 默认 gone 的 ivInfo，各条目按需设为 VISIBLE 即可拥有说明能力，其他条目零成本不受影响——"模板预留 + 按需显示"是列表条目扩展的轻量手法。

## 复盘与要点
- 说明类长文案直接放 strings 而非建专门布局，配合通用 SentinelDialogSmall，两条文案资源即完成一个功能点，性价比极高。
- 条目模板加默认隐藏的扩展槽（info 图标、tag 等）是控制布局文件数量的有效手段，但要注意约束冲突风险（标题过长时图标挤出）。
- `ic_about_info` 复用既有"关于"图标充当"!"标，视觉语义是否达标依赖设计确认。

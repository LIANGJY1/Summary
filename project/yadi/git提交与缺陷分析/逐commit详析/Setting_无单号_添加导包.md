# 无单号 · 添加导包

- **提交**：`d9720b65` | 2026-06-29 | dufan | Setting | feature
- **关联单**：无

**类型**：编译修复微提交（1 文件 +1 行）。

## 内容一句话
`SystemFragment.kt` 补上 `SentinelDialog` 的 import——上一提交 `51192409` 在该文件新增 `SentinelDialog().show(...)` 调用但漏了导包，导致编译失败，本提交单独补救。

```diff
# application/Setting/src/main/java/com/yadea/setting/ui/fragment/SystemFragment.kt
+import com.yadea.setting.ui.fragment.diologfragment.SentinelDialog
```

**复盘**：这是 `51192409` 的补丁提交，说明该开发者本地提交前未编译验证（IDE 自动导包未保存即提交）。廉价的预防手段：pre-commit 钩子跑一次 `compileDebugKotlin` 或至少 IDE 的"optimize imports + save"后再提交。

# SIR-1472 · 车设亮度无法调到最大值
- **提交**：`7a0d872f` | 2026-06-29 | liujinfeng | Setting | bugfix
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 主交互

## 问题
车控车设里把屏幕亮度拉满，控制中心/负一屏的亮度条没有同步到最大档，亮度实际调不到最大值。

## 根因分析
`DisplayFragment`（`application/Setting/.../ui/fragment/DisplayFragment.kt`）初始化自定义刻度亮度条 `sbScreenLight.gearBright` 时，把档位总数 `gearCount` 硬编码为 `20`。而亮度实际取值范围是 0~20 共 21 个档位（含 0 档），自定义控件按档位数均分刻度后，UI 上的"最后一格"只对应亮度值 19，永远映射不到最大值 20。把车设亮度条拉满只是拉到第 20 档，同步给控制中心的进度自然不是满值。缺陷库归因为"自定义控件档位设计错误"，与代码一致。

## 关键代码修改
改动文件：`application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt`
```diff
--- a/application/Setting/src/main/java/com/yadea/setting/ui/fragment/DisplayFragment.kt
@@ -42,7 +42,7 @@ class DisplayFragment : BaseFragment<FragmentDisplayBinding, DisplayViewModel>()
         mBinding.sbScreenLight.tvAuto.text = getString(R.string.auto_light)
         mBinding.sbScreenLight.gearBright.setPadding(0, 0, 0, 0)
-        mBinding.sbScreenLight.gearBright.gearCount = 20
+        mBinding.sbScreenLight.gearBright.gearCount = 21
```

## 为什么能修复
`gearCount` 改为 21 后，控件档位与亮度值域 0~20 一一对应，拉满即真实最大亮度，与控制中心的进度同步一致。风险很小，但要注意其它使用同一 `gearBright` 控件的界面是否也各自硬编码了档位数，需保持一致。

## 复盘与经验
- "档位数 = 最大值 + 1"是刻度条类控件的常见 off-by-one，硬编码前应推导值域而不是照抄视觉稿的格子数。
- 多个界面各自初始化同一个自定义控件时，档位这类参数最好收敛到控件默认值或常量，避免两处数字不一致造成状态不同步。

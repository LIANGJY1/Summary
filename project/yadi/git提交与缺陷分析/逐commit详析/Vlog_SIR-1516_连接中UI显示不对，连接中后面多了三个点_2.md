# SIR-1516 · Vlog 连接中 UI"连接中"后多出三个点

- **提交**：`0252d511` | 2026-07-08 | daizhecheng | Vlog | bugfix（UI 微调）
- **缺陷库**：等级 D · 频次 必现-80%~100% · 状态 关闭 · 域 车载vlog · 根因/方案均记为"UI问题"

## 问题
Vlog 相机连接过程中，按钮显示"连接中"后面多出三个点，UI 与设计稿不符。

## 根因分析
本提交 diff 仅 1 行：`activity_home.xml` 中连接加载图标 `iv_loading`（`@drawable/ic_white_loading`，36dp，默认 gone）被移除了 `android:layout_marginStart="@dimen/dp18"`。结合同单前序提交 `80f87a0c`（把误名为 `btn_search_device` 的"连接中…"文案拆分为 `btn_connecting`"连接中…"与"搜索中"）可见，该页的连接状态由 **文字按钮 + 三点式加载动画图标** 组合表达。`ic_white_loading` 是三点加载图，图标因多余 margin 未按设计对位，静止/未动画时三个点看起来像拼在"连接中"文字后面的省略号。**说明**：缺陷库仅记"UI问题"，上述视觉机制是从 1 行 diff 与同单前序提交推断的最合理解读，未能从 diff 完全确证。

## 关键代码修改
改动文件：`application/Vlog/src/main/res/layout/activity_home.xml`（-1）
```diff
// --- application/Vlog/src/main/res/layout/activity_home.xml  rl_start_pair 内
                 <ImageView
                     android:id="@+id/iv_loading"
                     android:layout_width="36dp"
                     android:layout_height="36dp"
                     android:layout_centerVertical="true"
-                    android:layout_marginStart="@dimen/dp18"
                     android:src="@drawable/ic_white_loading"
                     android:visibility="gone" />
```

## 为什么能修复
去掉多余 margin 后加载图标回到设计位置（覆盖/紧贴按钮区域），三点动画与"连接中"文字的组合不再呈现为"文字后多三个点"的错觉。改动零风险；但此类像素级问题说明设计稿→布局的还原缺少视觉走查环节。

## 复盘与经验
- **动画占位图与文案是组合表达**：按钮文案带/不带省略号、图标是否叠加，必须在设计规格里写清，否则"连接中…"（string）+三点图标会双重表达。
- 同一单号拆多次提交（80f87a0c 文案拆分、0252d511 布局对位）时，单据状态应等最后一次提交验证后再关闭。
- 一行 margin 引发的感知 bug，说明 UI 回归要走真机截图对比，代码 review 很难发现。

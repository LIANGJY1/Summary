# SIR-6618 · 深色模式下协议文案标点显示为黑色与 UI 不一致

- **提交**：`824cee31` | 2026-08-27 | liqingqing | AccountCenter | bugfix
- **缺陷库**：等级 C · 频次 必现-80%~100% · 状态 关闭 · 域 账号中心

## 问题
深色模式下首次进入 applist 的账号中心登录页，"《用户协议》，《隐私政策》。"一行中逗号、句号两个标点显示为黑色，深色背景上几乎不可见，与 UI 稿不符。

## 根因分析
登录页协议行被拆成多个 `TextView`：协议名（蓝色链接 `#007AFF`）与标点（`comma`、`period`）分属不同控件。标点控件的 `textColor` 硬编码为 `#000000` 纯黑——浅色模式下黑色标点与黑色正文视觉一致没有暴露，深色模式下背景变黑、标点仍是纯黑，立刻不可见（缺陷库：都用的黑色）。根源是未使用主题语义色 `text_default_default`，导致颜色不跟随深浅色切换。

## 关键代码修改
改动文件：`application/AccountCenter/src/main/res/layout/activity_login.xml`

```diff
--- a/application/AccountCenter/src/main/res/layout/activity_login.xml
@@ -47,7 +47,7 @@
                 android:text="@string/comma"
-                android:textColor="#000000"
+                android:textColor="@color/text_default_default"
                 android:textSize="24sp"/>
@@ -60,7 +60,7 @@
                 android:text="@string/period"
-                android:textColor="#000000"
+                android:textColor="@color/text_default_default"
                 android:textSize="24sp"/>
```

## 为什么能修复
`text_default_default` 是项目定义的语义色（在 values 与 values-night 下分别映射浅/深色值），标点颜色随主题自动切换：深色模式下变浅色可见，浅色模式下保持黑色不变。仅两处属性替换，无逻辑风险。隐患排查点：同页/同模块是否还有其他 `#000000` 硬编码文本色，深色模式适配时应全局清理。

## 复盘与经验
- 深浅色双主题项目的铁律：文本颜色一律用语义色资源（`text_default` 等），布局中出现 `#000000`/`#FFFFFF` 硬编码就是深色模式的定时炸弹——浅色下测试通过不代表没问题。
- "首次进入"才复现的细节（如标点颜色）提示深色模式验证要覆盖冷启动首屏，不能只测二次进入/动态切换路径。
- UI 拆分多控件的行（文字+链接+标点），非链接部分的颜色语义应与正文一致，评审时可对照 UI 稿逐控件核对颜色 token。

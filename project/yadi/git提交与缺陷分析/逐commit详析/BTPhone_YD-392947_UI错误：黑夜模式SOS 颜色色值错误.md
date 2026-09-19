# YD-392947 · 黑夜模式 SOS 颜色色值错误（应为 #FF3C3C）

- **提交**：`c2cb4d93` | 2026-07-29 | hedeyuan | BTPhone | bugfix
- **缺陷库**：未关联缺陷库记录（单号 YD-392947，defs 无条目）

## 问题
黑夜模式下拨号盘 SOS 按钮文字颜色与设计稿不符，设计要求统一为 #FF3C3C。

## 根因分析
`DialSosButton.updateTextColors()` 中 SOS 文字色引用的是本地 `R.color.red`。虽然 BTPhone 本地 `colors.xml` 中 `red` 的值就是 `#FF3C3C`，但它是一个模块内自维护的孤立色值，不在设计系统的语义色体系内（昼夜模式切换、主题库统一改版时不受管控），黑夜模式下的实际显示与设计规范脱节。设计系统的正确令牌是共享主题库的 `text_error_default`（语义化"错误/紧急"色，本仓库源码中无定义，来自外部主题资源库），其规范值即 #FF3C3C。

## 关键代码修改
改动文件：application/BTPhone/src/main/java/com/yadea/btphone/view/DialSosButton.java

```diff
--- application/BTPhone/src/main/java/com/yadea/btphone/view/DialSosButton.java
     private void updateTextColors() {
         if (numberText != null) {
-            numberText.setTextColor(ContextCompat.getColor(mContext,R.color.red));
+            numberText.setTextColor(ContextCompat.getColor(mContext,R.color.text_error_default));
         }
     }
```

## 为什么能修复
SOS 文字色改挂到设计系统的语义令牌 `text_error_default` 上，色值随主题库统一管控（规范值 #FF3C3C），黑夜模式与设计稿对齐；后续设计系统调色（如暗色下微调紧急红）也会自动生效。前提是主题库在目标构建环境中提供该资源（能编译通过说明已具备）。无逻辑副作用，属颜色令牌归位。

## 复盘与经验
- **即使色值"恰好相同"也要用语义令牌**：`R.color.red` 与 `text_error_default` 值相同但管控归属不同——本地色值是孤岛，语义令牌随设计系统全局演进，昼夜/品牌定制场景必选后者。
- **"应为 #FF3C3C"类单据要落到令牌而非写死**：把设计稿色值直接硬编码进代码会复刻同样的孤岛问题，正确闭环是"设计稿→主题库令牌→代码引用"。
- **SOS 这类安全相关 UI 的颜色需昼夜双模式验收**，颜色类 UI 单在提交说明中应注明两种模式下的期望色值，本单标题只写了黑夜模式表现。

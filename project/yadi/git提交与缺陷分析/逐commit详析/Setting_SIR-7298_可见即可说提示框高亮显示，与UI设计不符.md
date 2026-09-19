# SIR-7298 · 可见即可说提示框高亮夜间素材缺失

- **提交**：`660ca21e` | 2026-09-02 | sgh | Setting | bugfix（纯二进制素材更新）
- **缺陷库**：等级 B · 频次 必现-80%~100% · 状态 关闭 · 域 车控车设

## 问题
黑夜模式下"可见即可说"提示框的高亮图标沿用白天素材，与 UI 设计不符（视觉突兀/高亮错误）。

## 根因与修复说明
提交 [why] 为"未兼容黑夜模式"，[how] 为"加上黑夜模式素材"。改动为纯二进制资源新增，无代码逻辑变化：

- `application/Setting/src/main/res/drawable-night/ic_voice_1.png`（新增，约 12KB）
- `application/Setting/src/main/res/drawable-night/ic_voice_2.png`（新增，约 25KB）
- `application/Setting/src/main/res/drawable-night/ic_voice_3.png`（新增，约 6KB）

即给 `ic_voice_1/2/3` 三张语音提示高亮图补齐 `drawable-night` 限定符版本；白天引用原 `drawable/` 素材不变，夜间资源查找命中新目录中的深色版素材，提示框随之适配黑夜模式。

## 为什么能修复
Android 资源限定符机制保证夜间模式自动加载 `-night` 目录同名图片，无需任何代码改动；零逻辑风险。隐患仅在于素材命名/尺寸需与白天版严格同名同尺寸，且后续替换素材时容易再次只换一边。

## 复盘与经验
- 与 `fb8572d3`（补 drawable-night/dialog_smal.xml）同构：夜间适配遗漏既可能发生在 XML shape，也可能发生在 PNG 素材，检查清单应覆盖全部资源类型。
- 设计切图交付时应按"白天/黑夜"两套成组交付并核对目录名，开发侧收到切图只入 `drawable/` 不入 `drawable-night/` 是高频流程漏洞。

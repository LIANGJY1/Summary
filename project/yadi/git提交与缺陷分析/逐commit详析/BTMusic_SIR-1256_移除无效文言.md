# SIR-1256 · BTMusic 移除无效文言
- **提交**：`1194a945` | 2026-07-14 | daizhecheng | BTMusic | bugfix（文案清理）
- **缺陷库**：未关联单号（标题带 SIR-1256，defs 为空）

## 类型说明
清理类提交：删除 BTMusic 中不再使用的字符串资源，并顺手修正英文目录里的中文残留。不涉及逻辑，给要点说明。

## 改动内容
- application/BTMusic/src/main/res/values/strings.xml（-7）：删除 `add_other_device`、`search_nearby_bluetooth`、`current_playing_device`、`please_open_music_app`、`device`、`tab_bt` 等已无引用的条目。
- application/BTMusic/src/main/res/values-en/strings.xml（+2/-9）：删除同样一批无效条目；并把 `pre`/`next` 从中文"上一首/下一首"改为 `up`/`next`（英文目录残留中文属于真问题）。

## 说明
标题关联的 SIR-1256 缺陷库无详细记录（defs 为空）。实质有价值的修复点是 values-en 中 `pre`="上一首" 这类**英文语言环境下的中文文案残留**——切英文后上一首/下一首按钮会显示中文；其余为死资源清理。`values-en` 文件结尾无换行符（"\ No newline at end of file"）也侧面说明该文件长期缺乏维护。

## 复盘与经验
- **多语言目录是文案 bug 高发区**：默认目录改了、values-en 忘改（或反向残留），建议 CI 加 lint 校验各 locale 的键集合一致性与语言一致性。
- **删除资源前确认无引用**：本提交删的是"无效文言"，需以全局引用检索为依据，避免 layout/databinding 上的漏检导致运行时 crash。

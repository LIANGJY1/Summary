# screen_recorder — 屏幕录制 MP4 工具

录屏直接产出 AI 友好的 MP4（H.264 + yuv420p、15fps 默认、1920 宽等比缩放），用于「录操作过程 → 传给 AI 分析」。
GNOME 内置录屏（WebM/VP8/1000fps 假帧率）无法被 GLM 等视频通道解析（报 1210），本工具绕开它。

## 文件

| 文件 | 说明 |
|---|---|
| `record-gui` | GTK3 图形界面：整桌面 / 单屏幕 / 框选区域，悬浮条显示时长与停止按钮 |
| `record-mp4` | 免界面一键全屏录制：运行开始，再运行停止 |

## 集成入口（已配置）

- 快捷键 **Ctrl+Alt+G** 和 **Ctrl+Alt+R** → 都打开 `record-gui` 图形界面（与 Atlas 设置页按钮同一体验）
- `record-mp4`（无界面全屏开关）保留为 CLI 工具，未绑定快捷键
- 启动器 `~/.local/share/applications/record-mp4-gui.desktop`
- **Atlas 设置页「屏幕录制」**卡片：保存目录、帧率、码率、一键打开录屏界面

## 录制中的界面

- **悬浮停止条**（`● 时间  ■ 停止`）：置顶显示时长；按住 `● 00:12` 把手可拖动位置，`■ 停止` 按钮独立不受拖动影响
- **录制区域红色矩形边框**（带四角角标，通用样式）：覆盖所选范围，点击穿透不影响下层操作
- 区域选择为 slop 式全屏遮罩拖框，ESC 取消
- **录制结束自动弹出文件夹**：走 `org.freedesktop.FileManager1.ShowItems` 在文件管理器中打开目录并选中该视频（已有窗口则复用），接口失败退回 `xdg-open` 目录

## 参数配置（Atlas 托管）

两脚本启动时读 `~/.local/share/atlas/screen-recorder.json`（Atlas 设置页保存时写出，文件不存在用内置默认）：

```json
{"saveDir": "/home/liang/Videos/Screencasts", "fps": 15, "bitrate": 4000}
```

- `saveDir`：输出目录（不存在会自动创建）
- `fps`：record-gui 的默认帧率（界面里仍可临时改）
- `bitrate`：x264 目标码率 kbps

## 依赖

`gst-launch-1.0`（gstreamer1.0-plugins-good/ugly，x264enc 来自 ugly）、`python3-gi`（Gtk 3.0）、X11 会话。

## 已知坑（改代码前先看）

- `gst-launch-1.0` **按 argv 逐 token 解析**管线，整串当单个参数传入会报 syntax error（所以 `actual_pipeline` 返回**参数列表**，调用方用 `["gst-launch-1.0","-e"] + 列表` 拼接，不能再包一层）。
- 系统装有 GTK4 时必须 `gi.require_version("Gdk", "3.0")`，否则 Gdk 解析到 4.0 冲突。
- **HiDPI（2x 缩放）坐标语义**：GTK 事件/`Gdk.Screen.width()` 返回**逻辑像素**（物理/2），ximagesrc 的 startx/endx 和 xrandr 是**物理像素**。已实测校准（xdotool mousemove 2000,1000 → GTK 读到 1000,500）。框选坐标按 `物理宽/Gdk.Screen.width()` 比例换算。
- 停止靠 SIGINT/EOS 触发 mp4mux 正常收尾；强杀进程会得到无 duration 元数据的残缺 MP4。
- GNOME Shell 的 `org.gnome.Shell.Screencast` D-Bus 只支持全屏且要求客户端在录制期间保持连接（record-mp4 即此方案）；任意矩形采集走 `ximagesrc`（record-gui 方案），不依赖 GNOME 服务。
- **gnome-settings-daemon media-keys 的自定义快捷键有竞态**：先写 `custom-keybindings` 列表、后写键值时，daemon 可能在键值为空时读列表且之后不重新抓取（表现为快捷键无效）。修复：把 binding 改成别的值再改回来，强制 dconf 变更通知触发 re-grab。
- GUI 崩溃会写 `~/.local/share/atlas/screen-recorder-gui.log`（未捕获异常兜底），不再静默；同时有单实例守护（重复打开提示"已打开"）。

# Atlas Android（手机端）

Atlas 的 Android 手机版。**与桌面端共享全部核心代码**（`../app/src/main/kotlin` 里的
UI 与 core/Indexer 源码经 Gradle `srcDir` 直接引入编译），本目录只承载 Android 工程
壳与平台差异实现。PRD 见仓库 `atlas/PRD.md` §13。

## 目录结构

```
app-android/
├── settings.gradle.kts / build.gradle.kts / gradle.properties   # 独立 Gradle 工程（依赖走阿里云直连）
├── build-apk.sh                                                 # 构建脚本（JDK17 + gradle 8.14.3，同桌面 update.sh 约定）
└── app/
    ├── build.gradle.kts        # 共享源码引入 + 桌面专属文件排除清单
    └── src/main/
        ├── AndroidManifest.xml # 所有文件访问权限（MANAGE_EXTERNAL_STORAGE）
        └── kotlin/
            ├── atlas/android/  # Activity、底部导航壳、建库页+目录浏览器、设置页
            └── atlas/android/platform/Platform.kt  # 平台实现（与桌面 atlas/platform/ 同 FQCN）
```

## 平台差异机制

共享代码里的平台差异点统一收敛在 `atlas.platform.Platform`（同一 FQCN 两侧各一份实现）：
剪贴板、外部打开文件、拖拽光标、Tooltip、进程输出丢弃、进程树终结、UI 线程投递、默认库路径。
Android 构建排除的桌面专属文件（见 `app/build.gradle.kts`）：`Main.kt`（窗口壳）、
`WindowChrome.kt`、`ToolsView.kt`（adb/飞书/录屏工具）、`SettingsView.kt`（JFileChooser；
通用件已拆到 `SettingsScaffold.kt` / `TypographySettingsPage.kt` / `ThemeDialogs.kt`）、
`atlas/platform/`。

## 构建

```bash
./build-apk.sh                 # 产物 app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
```

## 手机端使用

1. 把知识库同步到手机共享存储，约定路径 `/sdcard/Atlas/knowledge-base`
   （git 客户端 clone / Syncthing / `adb push` 均可；个人侧载应用，无商店政策约束）。
2. 首次启动在系统设置里授予「所有文件访问权限」（建库页有跳转按钮）。
3. 建库页选择库根目录 → 打开。首次进入默认打开“浏览”，显示知识库内所有 Markdown 文档；“目录”可展开全部子目录，搜索支持按文件名、路径和文档正文查找。题库页的目录也展示全部知识库文档：含 `Qn` 标记的文档显示题目列表，点击题目进入全屏阅读并可前后切题；没有题目标记的文档直接显示 Markdown。题目搜索覆盖全库，手机端隐藏学习状态与编辑类操作；浏览位置与题库位置分别记忆。配置双层机制与桌面共用：
   本机层在应用私有目录，仓库同步层仍是 `<库根>/atlas/config/settings.properties`，随 git 多设备一致。

## 已知限制（MVP）

- 不含工具页：adb 设备工具箱、飞书打卡、录屏等 PC 侧自动化在手机上无意义。
- 「编辑器打开」降级为提示；题目相对 git HEAD 的改动标记依赖 git 可执行文件，手机端不可用（自动降级为不标色）。
- Android SQLite 不提供 FTS5 时自动回退为 LIKE 搜索；命中文档范围取决于当前索引规则。
- 手机主流程面向只读阅读；题库编辑、git 更新、PC 工具不作为手机功能入口。
- 桌面壳层（无边框窗口、Ctrl+K、鼠标侧键导航）不适用手机端，由底部导航 + 系统返回键替代。

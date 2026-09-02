# honda27m-appstore-tools — Honda 27M AppStore 车机 UI走查 工具链

本目录是 Honda 27M 车机平台 AppStore 应用（AppStoreApp）的 **UI走查配套工具**：
通过 ADB 驱动车机自动截取各页面实机图，与 UE 设计稿（UI图）按三位序号配对，
生成自包含的并排对比 HTML 报告（含像素级差异高亮），供 UI走查 肉眼比对与回归。

> 工具用法详见 [screenshot/README.md](screenshot/README.md)；本文件记录**本目录的来历、
> 与原项目的关系和外部依赖**，防止日后看到本目录时不知从何而来、依赖何处。

## 来源与迁移记录

| 项 | 内容 |
|----|------|
| 原位置 | `/home/liang/Project/Reachauto/HC/27M/Honda27M/AppStore/tools/screenshot/` |
| 原项目 | Honda 27M AppStore Android 工程（branch `master`，迁移时 HEAD `72d34cb4`，2026-09-01） |
| 迁移原因 | `tools/` 与 App 业务代码无关，被原仓库 `.gitignore` 全局忽略（`tools/` 一行，0 个被跟踪文件），从未纳入版本管理；迁出独立管理 |
| 迁移方式 | 2026-09-01 整目录 `mv`，无 git 历史可继承（原本就没有）；原位置已不存在 `tools/` |
| 本仓库首次入库 | Summary 仓库 `main` 分支 commit `9f6a193`（"add honda27m-appstore-tools"，11 个文件） |
| 迁移时同步修改 | ① 源码/文档中的 `tools/screenshot/...` 命令示例改为 `honda27m-appstore-tools/screenshot/...`；② `compare_report.py` 新增实机图目录 `<模式>_split` 自动回退（修复分屏模式配对 0 的问题，见下文「_split 后缀约定」） |

## 与 Honda27M AppStore 项目的关系

**单向、纯 ADB、零文件耦合。** 本工具通过 `adb shell` 驱动装在车机上的 App 完成
导航、Mock 场景切换、截图与拉取；不引用 AppStore 仓库的任何文件，构建本工具不需要该仓库。
反过来，AppStore 仓库也从未版本化管理过本工具。

因此：**AppStore 项目即使搬迁、改名或删除，本工具仍可独立运行**——只要还有车机（adb）
和 UI 设计稿库（见下节）。

### 被测对象（App 侧关键标识）

| 标识 | 值 | 说明 |
|------|-----|------|
| 应用包名 | `com.hynex.appstoreapp` | AppStoreApp，车机 launcher UI |
| 服务包名 | `com.hynex.appstoreservice` | App Store IPC 服务，Mock 场景经其生效 |
| Mock 场景 action | `com.hynex.appstoreservice.MOCK_SCENARIO` | 截图前切换模拟数据 |
| 主页 Activity | `.home.MainActivity` | |
| 搜索/详情 Activity | `.feature.search.SearchAppActivity` / `.feature.detail.AppDetailActivity` | |
| Debug Helper | `.screenshot.ScreenshotDebugActivity` | 仅 debug 构建包含，弹窗类画面靠它拉起 |

这些值硬编码在 `screenshot/capture_appstore_screenshots.py` 顶部。若原项目改了包名或
Activity 类名，需同步修改该文件（AGENTS.md 约定：SDK 侧还有硬编码目标，改包名是跨模块动作）。

### 仅当需要以下操作时才回原项目仓库

原项目仓库（若仍存在）：`/home/liang/Project/Reachauto/HC/27M/Honda27M/AppStore`

| 需求 | 回原仓库改什么 |
|------|----------------|
| App 页面/弹窗结构变化导致截图画面过时 | `AppStoreApp/` 业务代码，重新构建安装 debug APK 后重截 |
| 需要 new 弹窗/状态走查 | `AppStoreApp/src/debug/java/com/hynex/appstoreapp/screenshot/ScreenshotDebugActivity.java` 新增 action，并在本工具 `build_tasks()` 中加任务 |
| Mock 数据不对 | `AppStoreService/src/debug/assets/mock/{scenario}/` 下的 JSON（接口映射表见 screenshot/README.md） |

原仓库根目录的 `AGENTS.md` / `CONTEXT.md` 记录了工程结构与词汇表（UI图、实机图、
UI走查、截图任务、Mock 场景、Debug Helper、差异率阈值 30 等定义），路径见上。

## 外部依赖（均不在本仓库，注意备份）

| 依赖 | 位置 | 说明 |
|------|------|------|
| **UI 设计稿库（UI图）** | `~/Documents/HC/UI/extracted_images/` | 264 张（全屏 38×4 + 分屏 24×4 + 服务激活 16），按 `AppStore_全屏<cn\|en>_<D\|L>`、`分屏<cn\|en>_<D\|L>`、`服务激活` 分目录；提取自桌面 xlsx `Honda_Connect5.0_UI_CheckList_AppStore0901.xlsx`（WPS DISPIMG 解析，仅 D 列）。**本工具的比对基准，无备份机制，丢失需从 xlsx 重新提取**；详见该目录 README |
| **车机** | adb 连接、已开启调试 | 已安装含 `ScreenshotDebugActivity` 的 debug APK；不同分辨率车机的点击/滑动坐标在本目录 `screenshot/coordinates.json`、`screenshot/split_coordinates.json` 校准（这两个文件已入库） |
| Python + PIL | 本机 | Python 3.8+；PIL 可选，缺失时报告退化为纯并排无差异列 |

UI 设计稿根目录 `UI_REF_ROOT = Path.home() / "Documents/HC/UI/extracted_images"`
硬编码在两个截图脚本内各自维护（全屏 `capture_appstore_screenshots.py`、
分屏 `capture_appstore_splitscreenshots.py`，二者完全独立、互不依赖），
设计稿库搬家时两处都要改。

## 目录结构与数据说明

```text
honda27m-appstore-tools/
├── README.md                 # 本文件：来历与关系记录
└── screenshot/               # 工具本体（原 tools/screenshot 原样迁移）
    ├── capture_appstore_screenshots.py     # 全屏一键截图（模式在顶部 CURRENT_VARIANT 切换）
    ├── capture_appstore_splitscreenshots.py# 分屏一键截图（独立脚本：自带 CURRENT_VARIANT/SPLIT_VARIANTS 配置与 ADB 基础设施，输出 screenshots/<模式>_split/）
    ├── compare_report.py                   # UI图↔实机图 并排对比报告（跟随当前模式）
    ├── compare_split_report.py             # 分屏版对比报告（UI图 映射到 分屏cn_D 等）
    ├── fix_keyboard.py                     # 车机输入法诊断修复（截图中断后 ime 残留）
    ├── coordinates.json / split_coordinates.json  # 车机坐标校准（入库）
    ├── walkthrough.md / walkthrough-analysis.md   # 走查记录（入库）
    ├── README.md                           # 工具用法详解
    ├── screenshots/          # 【不入库】实机截图素材（~53MB），adb 重截可重建
    └── report/               # 【不入库】生成的对比报告（~314MB），脚本重跑可重建
```

`screenshots/`、`report/`、`__pycache__/` 由本目录 `.gitignore` 排除——只进代码和文档，
实机素材与生成物留作本地工作数据。

## 模式体系速查

一个**模式** = 语言 × 昼夜 × 形态（`<语言>_<昼夜>_<形态>`，如 `zh_dark_split`），
共 8 个登记模式，自动映射 实机图目录 ↔ UI图目录 ↔ 报告目录：

| 模式 | 实机图（screenshots/） | UI图（extracted_images/） |
|------|------------------------|---------------------------|
| zh_dark_fullscreen | `zh_dark_fullscreen/` | `AppStore_全屏cn_D` |
| en_dark_fullscreen | `en_dark_fullscreen/` | `AppStore_全屏en_D` |
| zh_day_fullscreen | `zh_day_fullscreen/` | `AppStore_全屏cn_L` |
| en_day_fullscreen | `en_day_fullscreen/` | `AppStore_全屏en_L` |
| zh_dark_split | `zh_dark_split_split/` ① | `分屏cn_D` |
| en_dark_split | `en_dark_split_split/` ① | `分屏en_D` |
| zh_day_split | `zh_day_split_split/` ① | `分屏cn_L` |
| en_day_split | `en_day_split_split/` ① | `分屏en_L` |

① **_split 后缀约定**：分屏截图脚本（`capture_appstore_splitscreenshots.py`，独立脚本）
默认输出 `screenshots/<模式>_split/`。`compare_report.py` 已做自动回退（配置目录不存在而
`<目录>_split` 存在时自动改用后者，2026-09-01 修复，此前该问题导致配对恒为 0）；
`compare_split_report.py` 则始终按 `<模式>_split` 解析（配置只读分屏脚本顶部 CURRENT_VARIANT）。

截图任务三位序号与 UI图 一一对应：全屏 001–038、分屏 001–024、服务激活 001–016。

## 快速上手

```bash
# 1. 截实机图：改 capture_appstore_screenshots.py 顶部 CURRENT_VARIANT 后固定命令运行
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py

# 2. 生成并排对比报告（自动跟随当前模式，含 _split 自动回退）
python3 honda27m-appstore-tools/screenshot/compare_report.py
#    分屏走查用分屏版报告：
python3 honda27m-appstore-tools/screenshot/compare_split_report.py

# 3. 浏览器打开 report/<模式>/index.html 逐张比对，结论记入 walkthrough.md
```

## 失联恢复指引

- **看到本目录不知来历** → 读本文件「来源与迁移记录」。
- **原 AppStore 项目搬迁/改名/删除** → 本工具不受影响，仅需更新本文件中的原仓库路径；
  若包名/Activity 改名，同步 `screenshot/capture_appstore_screenshots.py` 顶部常量。
- **UI 设计稿库搬家** → 改 `screenshot/capture_appstore_screenshots.py` 和
  `screenshot/capture_appstore_splitscreenshots.py` 各自的 `UI_REF_ROOT`（两个脚本独立维护）。
- **换新车机分辨率** → 重校 `screenshot/coordinates.json` / `split_coordinates.json`，
  方法见 screenshot/README.md「配置 JSON 说明」。

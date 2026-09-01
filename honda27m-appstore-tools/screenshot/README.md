# AppStoreApp 车机 UI 截图工具

用于在目标车机上自动截取 AppStoreApp 各页面实际效果图，与参考图进行对比。

## 设计原则

- **零侵入**：不修改 `AppStoreApp` 既有业务代码，辅助逻辑全部放在 `src/debug`。
- **ADB 驱动**：以 `adb shell` 命令完成页面跳转、输入、截图、拉取。
- **渐进实现**：先跑通 ADB 直接可达的页面；对需要特定状态/弹窗/数据的页面，通过 debug helper 补充。

## 文件说明

| 文件 | 说明 |
|------|------|
| `capture_appstore_screenshots.py` | 一键截图脚本（模式在脚本顶部配置区一键切换；支持 `--only 序号`、`--category 分类`、`--launch-only`） |
| `compare_report.py` | UI图 ↔ 实机图 配对并排 HTML 报告生成器 |
| `walkthrough.md` | UI走查对照表（38 张图的走查状态与差异记录） |
| `coordinates.json` | 不同分辨率车机的点击/滑动坐标校准 |
| `fix_keyboard.py` | 车机输入法诊断与一键修复（截图脚本中断导致 `ime disable` 残留时使用） |

## 术语

见仓库根目录 [CONTEXT.md](../../CONTEXT.md)：**UI图** = UE 设计稿；**实机图** = 车机实拍画面。

## 前置条件

- Python 3.8+
- adb 已安装且在 PATH 中
- 车机已连接 adb 并开启调试
- `com.hynex.appstoreapp` debug APK 已安装（含 `ScreenshotDebugActivity`）

## 用法

```bash
# 命令永远固定不变；要截哪个模式，改脚本顶部 CURRENT_VARIANT 一行后直接运行
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py

# 只跑指定序号 / 分类（与模式无关，随时可用）
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py --only 001,013,015
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py --category mine

# 查看全部模式、输出路径与当前生效项
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py --list-variants

# 可选：本次临时覆盖模式 / 指定输出根目录 / 多设备 serial（均不改脚本）
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py --variant en_dark_fullscreen
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py -o ./out/screenshots -d <device_serial>
```

### 截图模式与目录结构

一个 **模式** = 语言 × 昼夜 × 屏幕形态的组合，写入各自独立子目录，互不覆盖：

```text
honda27m-appstore-tools/screenshot/screenshots/
├── home|search|...          # 旧版基线截图（无变体层，保留不迁移）
├── zh_dark_fullscreen/      # 中文 · 黑天 · 全屏（默认）
│   ├── home/
│   └── ...
├── en_dark_fullscreen/      # English · 黑天 · 全屏
├── zh_day_fullscreen/       # 中文 · 白天 · 全屏
└── zh_dark_split/           # 中文 · 黑天 · 分屏
```

模式配置集中在 `capture_appstore_screenshots.py` 顶部的「一键模式配置区」：

```python
CURRENT_VARIANT = "zh_dark_fullscreen"   # ← 要截哪个模式就改成哪个 key
VARIANTS = {
    "en_dark_fullscreen": {
        "desc": "English · 黑天 · 全屏",
        "output_dir": "screenshots/en_dark_fullscreen",  # 实机图输出子目录（相对脚本目录，可绝对路径）
        "ref_dir": None,                                 # 该模式 UI图 目录；compare_report 作默认比对基准
        "device_setup": [                                 # 任务前逐条 adb shell 执行的环境准备命令
            # "settings put system system_locales en-US",
        ],
    },
    ...
}
```

- 切换模式：只改 `CURRENT_VARIANT`；截图与对比报告两条命令都保持不变，自动跟随。
- `ref_dir` 未配置（None）时，compare_report 回退其内置默认 UI图 目录。

### 模式 ↔ 实机图 ↔ UI图 一一对应

由模式名 `<语言>_<昼夜>_<形态>` 自动映射到 `~/Documents/HC/UI/extracted_images` 子目录
（zh→cn、en→en；dark→D 黑天、day→L 白天；fullscreen→`AppStore_全屏`、split→`分屏`），
无需手工配置：

| 模式 | 实机图目录（screenshots/） | UI图目录（extracted_images/） |
|------|------------------------|------------------------------|
| zh_dark_fullscreen | `zh_dark_fullscreen/` | `AppStore_全屏cn_D` |
| en_dark_fullscreen | `en_dark_fullscreen/` | `AppStore_全屏en_D` |
| zh_day_fullscreen  | `zh_day_fullscreen/`  | `AppStore_全屏cn_L` |
| en_day_fullscreen  | `en_day_fullscreen/`  | `AppStore_全屏en_L` |
| zh_dark_split      | `zh_dark_split/`      | `分屏cn_D` |
| en_dark_split      | `en_dark_split/`      | `分屏en_D` |
| zh_day_split       | `zh_day_split/`       | `分屏cn_L` |
| en_day_split       | `en_day_split/`       | `分屏en_L` |

- 特殊命名需求时用 `VARIANTS[mode].ref_dir` 手动覆盖自动映射。
- `--list-variants` 随时查看全部对应关系及当前生效模式。
- 自定义模式名可直接赋给 `CURRENT_VARIANT` 或用 `--variant <名称>`，自动落 `screenshots/<名称>/`。
- 输出目录优先级：显式 `-o`（作为根目录追加模式子目录）> `VARIANTS[mode].output_dir` > 默认。

## 当前实现状态

| 序号 | 页面 | 脚本状态 | 备注                                 |
|------|------|----------|------------------------------------|
| 001-017 | 首页 / 预装弹窗 / 搜索基础页 | 已验证（脚本中注释归档，`--only 序号` 可单独重跑） | |
| 018 | 搜索热门推荐无网络 | 已启用 / **已验证** | `search_top_error`                |
| 019 | 我的应用列表-全部更新 | 已启用 / **已验证** | `mine_all_update`                  |
| 020 | 我的应用加载中 | 已启用 / 未验证 | 依赖真实网络，loading 可能一闪而过              |
| 021 | 我的应用无网络 | 已启用 / **已验证** | 飞行模式 + `mine_no_network`           |
| 022 | 我的应用列表-卸载中 | 已启用 / 未验证 | `mine_uninstalling`                |
| 023 | 删除确认弹窗 | 已启用 / 未验证 | Helper 已实现 |
| 024 | 设置页 | 已启用 / 未验证 | 无 mock                             |
| 025-029 | 设置弹窗 / HCC 弹窗 | 已启用 / 未验证 | Helper 已实现 |
| 030-037 | 应用详情 / 预览图弹窗 | 已启用 / 未验证 | Helper 已实现 |
| 038 | 三方应用通用走行限制 | 已启用 / 未验证 | Helper 已实现 |

> 截图任务与 UI图 共用三位序号；走查进度见 [walkthrough.md](./walkthrough.md)。

## 配置 JSON 说明

### 1. `coordinates.json`

位置：`honda27m-appstore-tools/screenshot/coordinates.json`

用于适配不同分辨率车机，所有值直接作为 `adb shell input` 命令执行。

```json
{
  "tab_mine": "input tap 1100 120",
  "tab_mine_settings": "input tap 120 330",
  "swipe_home_down": "input swipe 960 750 960 200 400",
  "swipe_detail_down": "input swipe 960 700 960 300"
}
```

| 字段 | 说明 |
|------|------|
| `tab_mine` | 点击顶部“我的”Tab |
| `tab_mine_settings` | 点击 Mine 页左侧“设置”入口 |
| `swipe_home_down` | 首页向下滚动 |
| `swipe_detail_down` | 应用详情页向下滚动 |

### 2. Mock 响应 JSON

位置：`AppStoreService/src/debug/assets/mock/{scenario}/`

Service 接口与文件名映射：

| JSON 文件 | 对应接口 | 说明 |
|-----------|----------|------|
| `appstore_list.json` | `appstore/list` | 首页/分类应用列表 |
| `appstore_search.json` | `appstore/search` | 搜索结果 |
| `appstore_top.json` | `appstore/top` | 搜索页热门推荐 |
| `appstore_text.json` | `appstore/text` | 文本类接口（如搜索热词） |
| `appstore_detail.json` | `appstore/detail` | 应用详情 |
| `appstore_myList.json` | `appstore/myList` | 我的应用云端列表 |
| `appstore_download.json` | `appstore/download` | 下载地址响应 |
| `installed_apps.json` | 本地已安装列表 | 仅 mine 场景，绕过 DB 直接返回 |

统一外层格式（`appstore_download.json` 除外）：

```json
{
  "code": "000000",
  "description": "success",
  "responseBody": { ... }
}
```

- `code="000000"` 表示成功，其他值表示失败。
- 若当前场景缺少某个文件，自动回退到 `mock/default/` 下的同名文件。

### 3. `installed_apps.json`

仅用于 mine 场景，字段基本同 `AppListItem`，额外支持：

| 字段 | 说明 |
|------|------|
| `mockState` | `update` / `open` / `downloading`，控制右侧按钮状态 |
| `appIcon` | 使用 `file:///android_asset/mock_images/xxx.png` 等本地 URI |

### 4. 图片资源

- 图标、预览图放在 `AppStoreService/src/debug/assets/mock_images/`（或对应场景 `images/`）。
- JSON 中用 `file:///android_asset/...` 引用，Glide 可直接离线加载。

## Debug Helper

位于 `AppStoreApp/src/debug/java/com/hynex/appstoreapp/screenshot/ScreenshotDebugActivity.java`，仅 debug 构建打包，不进入 release。

支持通过 adb action 触发：

```bash
# 应用详情（使用真实 packageName，如网易云音乐）
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DETAIL \
    -e packageName com.netease.cloudmusic -e appName 网易云音乐 -e appVersionId 1047 \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# 自动更新弹窗
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_AUTO_UPDATE \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# 还原确认弹窗
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_RESTORE \
    -e previousVersion V1.0 -e currentApps 'App1,App2' \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# 图片预览弹窗
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_IMAGE_PREVIEW \
    -e imageUrls 'url1,url2' -e previewIndex 0 \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# 预装应用更新确认
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_PRE_APP_UPDATE \
    -e appType 2 -e appName '百度地图' -e apkSize '156MB' -e preAppName '百度地图汽车版' \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# 预装应用安装确认
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_PRE_APP_INSTALL \
    -e appName '百度地图' -e preAppName '百度地图汽车版' \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# 删除确认弹窗
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_DELETE \
    -e packageName com.hynex.demo.app -e appName DemoApp \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

# HCC 弹窗
adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_HCC \
    -e hccContent 'Honda Connect Core<br>版本信息' \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_HCC_LOADING \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity

adb shell am start -a com.hynex.appstoreapp.screenshot.SHOW_DIALOG_HCC_FAILED \
    -n com.hynex.appstoreapp/.screenshot.ScreenshotDebugActivity
```

## 走查流程（UI图 ↔ 实机图）

```bash
# 1. 截实机图（全部启用任务，或按分类/序号筛选）
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py
python3 honda27m-appstore-tools/screenshot/capture_appstore_screenshots.py --category mine

# 2. 生成并排对比报告：默认跟随截图脚本当前模式（CURRENT_VARIANT），
#    自动取 screenshots/<模式>/ 与该模式的 ref_dir，报告输出 report/<模式>/index.html
python3 honda27m-appstore-tools/screenshot/compare_report.py

# 3. 浏览器打开 report/index.html 逐张肉眼比对，
#    把结论记入 walkthrough.md（✅一致 / ⚠️细节差异 / ❌不一致 / ⬜未截）
```

`compare_report.py` 常用参数：

| 参数 | 默认 | 说明 |
|------|------|------|
| `--ref-dir` | 模式配置的 `ref_dir`，未配置回退 `~/Documents/HC/UI/extracted_images/AppStore_全屏cn_D` | UI图 目录 |
| `--actual-dir` | `screenshots/<当前模式>/` | 实机图目录（含 category 子目录），跟随截图脚本 CURRENT_VARIANT |
| `--out` | `report/<当前模式>/index.html` | 报告输出位置，各模式互不覆盖 |

报告为自包含 HTML：图片复制进 `report/` 同级目录，可直接发给他人查看。

## 扩展指南

1. **补充 ADB 直接可达页面**：在 `build_tasks()` 中新增 `ScreenshotTask`，`adb_direct=True`。
2. **强制失败/异常状态**：任务中已使用 `cmd connectivity airplane-mode enable`；脚本执行完成后会自动恢复网络。
3. **需要 AppInfo/弹窗/Fragment 的页面**：扩展 `ScreenshotDebugActivity` 的 action，并在 `build_tasks()` 中调用。
4. **Tab/滚动坐标**：脚本内置了基于 1920x1080 模拟器的坐标，实际车机若分辨率不同，可在 `build_tasks()` 中修改 `TAB_MINE`、`TAB_MINE_SETTINGS`、`SWIPE_HOME_DOWN`、`SWIPE_DETAIL_DOWN`。

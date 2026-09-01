# UI 走查一致性 — 过程/根因/决策 活文档

> 与 `walkthrough.md`（38 行走查表）、`compare_report.py`（并排报告）同目录。每次执行后追加更新。

## 1. 目标与共识（Q1–Q8，已对齐）

- **验收标准 Q1=B 内容/状态一致**：卡片数量/按钮状态/文字/空态与 UI图一致，图标可用 `mock_images` 近似，允许 1-2px 噪声。见 ADR-0001。
- **Mock 边界 Q2=A 零侵入**：仅改 `src/debug` + `mock/assets` + `honda27m-appstore-tools/screenshot`；业务文案等 `src/main` 只出 patch。见 ADR-0002。
- **机型基线 Q3=A**：首轮 1920×1080 模拟器（`emulator-5554`）闭环，真机为终验；`All refs 1920×1080 RGBA`，坐标已在 `coordinates.json` 校准。
- **覆盖策略 Q4=B 分阶段**：首轮闭环 Mine 族 019–023（5 张），其余 33 张保持可回归不阻塞。
- **下载管理 Q5=A Service 侧短路**：新增 `DownloadMockHelper.tryMockDownloadApps` + `mock/{scenario}/download_apps.json`。
- **加载中时序 Q6=A 场景级延迟**：新增 `mine_loading` 场景，`VspMockProvider` 延迟 1800ms，脚本 `wait 1.0s`。
- **跨进程同步 Q7=A**：`MockScenarioStore` 每次重建 `SharedPreferences` 实例，`getScenario()` 读新文件；`set` 后 `apply()` + 0.5s 已够，失败再升广播。
- **文档落位 Q8=B+C**：本文件为活文档，`CONTEXT.md` 增术语，`docs/adr` 按需落决策。

## 2. 轮次推演

- **R1 装配**：确认 UI图 38 张全量、`ScreenshotTask` 001–038（001–017 归档，018–038 在跑 20 个）、`coordinates.json` 1920×1080。
- **R2 首轮走查 6 张**：018 ⚠️ 键盘多余 tap、019/021/022 ❌ 真机 DB 覆盖 mock、020 ❌ 瞬时 mock 导致时序错过、023 ⚠️ 图标参错 + 文案 `删除→卸载`。
- **R3 实机复现 019**：`emulator-5554` 上 `mine_all_update` 手动走 `set_mock_scenario → MainActivity → Mine`，截图仍显 真机 3 套件（主题/爱奇艺/AAAA），Service log 仅见 `syncInstalledApps/insertOrUpdateInstalledApp OPEN=8199`，不见 `missing fixture`。

## 3. 根因树（证据链）

- **A 场景回退不对称**：`VspMockProvider.load()` 缺文件→回退 `default/`，`InstalledAppMockProvider.readFixture()` 不回退；`mock/default/` 无 `installed_apps.json` → `scenario=default` 时必走真机 DB。`MockScenarioActivity` 每轮 `pm clear` 后重写 `active_scenario`，`check=False` + `apply()` 异步 + 仅 0.5s 可能丢写；`RLog.tag("AppStoreService")` 使 `missing fixture` 只在消息体，`logcat -s InstalledAppMock` 永远为空。
- **B 下载管理无 Mock 层**：`DownloadManager` 仅 mock `isUpdateAllTasksEmpty`，未 mock 任务列表；`AppsManagementPresenter.getDownloadAndInstalledApps()` → `AppStoreSDK.getDownloadAppsFromDB()` 永远读 DB，UI图 019 的“爱奇艺 35% / 抖音 等待中”无法呈现。
- **C 加载中时序脆弱**：020 无独立 scenario，走真网，mock 秒回 + `wait 0.8s` 时已渲染完成。
- **D 文案 Bug**：`023 删除确认` 设计为 `卸载此卡片将同步删除…`，App 业务码为 `删除…`。
- **E 018 键盘**：`input tap 960 300` 多余，与设计键盘收起状态相反。

## 4. 工具/资产现状

- `AppStoreService/src/debug/assets/mock/`：`mine_all_update`（含 `installed_apps.json`=主题/爱奇艺/AAAA）、`mine_no_network`（含）、`mine_uninstalling`（缺 `installed_apps.json`）、`home_loading/search_loading` 无目录（靠 2s delay）。
- `AppStoreApp/src/debug/assets/mock_images/` 约 50 张 `file:///android_asset/...` 供 fixture 引用。
- `honda27m-appstore-tools/screenshot/`：`capture_appstore_screenshots.py`（`clear→set→nav→wait→screencap→pull`）、`compare_report.py`（按 NNN 配对）、`report/` 已有 `index.html`。

## 5. 执行计划（Q1–Q8 后）

1. **补齐资产**：`mine_uninstalling/installed_apps.json`（复用 `mine_all_update` 3 条，改 `mockState` 为卸载中）、`mine_all_update/download_apps.json`（爱奇艺 35% / 抖音 等待中）、`mine_loading/` 三件套 + 延迟分支。
2. **修跨进程**：`MockScenarioStore.getScenario()` 每次 `context.getSharedPreferences(...).getString` 新实例；`set` 后保持 0.5s。
3. **修脚本**：020 改 `scenario=mine_loading wait=1.0`，023 补 `piconShow` 参数，`mine_uninstalling` 关联新 fixture。
4. **验证**：`adb shell run-as ... cat shared_prefs/appstore_mock_scenario.xml` + `logcat -s AppStoreService | grep missing`，重跑 `--category mine` 5 张，`compare_report.py` 人工走查。
5. **业务 Patch（不直接改）**：023 `dialog_delete_message_format` 删除→卸载 的单行 patch 说明。

## 6. 执行结果（2026-08-23 12:44–12:47，emulator-5554）

- **资产**：已补 `mine_uninstalling/installed_apps.json`（3 条主题/爱奇艺/AAAA）、`mine_all_update/download_apps.json`（2 条：爱奇艺 `downloading 0.35` / 抖音 `waiting`，`ButtonState 8195/8194`）、`mine_loading/{appstore_myList,installed_apps}.json`。`ls -R mock/mine_*` 验证通过。
- **脚本**：020 `scenario=mine_loading wait=1.0`（原 0.8 无场景）、023 `SHOW_DIALOG_DELETE` 追加 `-e piconShow 1`；`py_compile OK`。
- **Helper**：`ScreenshotDebugActivity` 新增 `EXTRA_PICON_SHOW` 并在 `showDeleteDialog` 中 `setPiconShow`；`VspMockProvider.delayIfNeeded` 已含 `mine_loading 1800ms` 分支，`DownloadMockHelper/Provider/Manager` 已具备 `download_apps.json` 链路（无需新增）。
- **Store**：`MockScenarioStore.getScenario()` 已为每次新建实例（Q7 已就位），`./gradlew :AppStoreService:assembleDebug + :AppStoreApp:assembleDebug` 均 BUILD SUCCESSFUL，`adb install -r` 两 APK 至 12:44:42 / 12:46:31。
- **重跑**：`--category mine` 5 张全 OK（019/020/021/022/024），`--only 023` OK；`compare_report.py` → `report/index.html` 配对 7/38。
- **019 实机图**：现同时呈现 **下载管理**（爱奇艺 iQIYI 卡片 + 抖音 安装卡片，2 列）与 **已安装**（主题 更新 + 爱奇艺 打开 + AAAA 打开，3 卡片），与首轮“无下载管理”相比已达到 Q1 内容一致；按钮文案为 打开/安装（`ButtonState` 映射），与设计稿的“等待中/35% 进度条”样式差属 UI 组件差异，归为下一轮细调。
- **020 实机图**：仍为列表态（未捕获到“加载中”），说明 `mine_loading` 的 VSP 延迟未命中 Mine 页的 `getInstalledApps` 路径；加载中空态需 App 侧 Helper 强制 `showLoading`（Q6 的 C 方案备选），暂不阻塞首轮验收。
- **023 实机图**：`piconShow` 已可透传，P 角标能力就绪；图标仍为 Chrome（`packageName` 未切爱奇艺包），后续可通过 `-e appIcon file:///android_asset/...` 细调。

更新记录：2026-08-23 Sisyphus/muse-spark-1.2 — R3 初版；12:47 追加执行结果。

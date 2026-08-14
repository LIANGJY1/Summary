# uicheck — 应用商店 UI 元素位置校对工具

像素级对比「UI 设计稿」与「车机截图」中每个矩形元素（卡片/横幅/按钮/面板）的
位置、尺寸、间距、边距，输出差异报告，把像素差直接映射到源码修改。

## 为什么有效

- 车机屏幕 1920×1080、密度 1.0 → **1px == 1dp**。
- 页面由 `RecyclerView` 网格 + XML margin + `ItemDecoration` 间距构成。
- 因此测出的 px 差值，1:1 对应布局 XML 里的 dp 值或 Java 里的间距值。

## 依赖

```bash
python3 -m pip install numpy pillow   # 无需 scipy，无需视觉模型
```

## 可视化 Web 界面（推荐）

零依赖本地 Web 工具，浏览器里可视化比对、勾选、导出：

```bash
cd tools/uicheck
python3 server.py --port 8765
# 浏览器打开 http://127.0.0.1:8765/
```

页面功能：

- **设计稿 / 车机截图** 两个画布，按类型着色画出每个元素（卡片/按钮/图标/文字/面板/分隔线）。
- **差异叠加**：绿框=设计位置，红框=实际位置，黄箭头=位移方向与大小，一眼看出往哪偏、偏多少。
- **像素差异热力图**：红=差异大，可发现内容/渲染级差异（不止布局）。
- **未对齐列表**：每项显示 `dx/dy/dw/dh`，可勾选要修改的元素。
- **导出勾选**：把选中元素 + 差值 + 「像素→源码」映射表导出成 JSON，直接照着改。

文件：`server.py`（stdlib http 服务）、`static/index.html`（前端，vanilla JS + Canvas）。

## 命令行用法

```bash
cd tools/uicheck
python3 uicheck.py <设计图.png> <车机截图.png>                 # 打印差异报告
python3 uicheck.py 设计图.png 截图.png --out report.md --json diff.json
python3 uicheck.py 设计图.png 截图.png --fail-delta 2          # 有元素位移>2px 时退出码=1（可做 CI 门槛）
```

关键参数：

| 参数 | 默认 | 说明 |
|------|------|------|
| `--tol` | 10 | 与背景的最大通道色差，低于此值视为背景 |
| `--min-area` | 500 | 最小矩形面积(px²)，过滤噪点 |
| `--solidity` | 0.6 | 实心矩形填充率阈值（用于「网格行」分析） |
| `--dilate` | 8 | 水平膨胀半径，把文字笔画连成文字行 |
| `--min-delta` | 2 | 只报告位移/尺寸差 ≥ 该 px 的元素 |
| `--fail-delta` | 0 | 任一元素 dx/dy/dw/dh 超过该值则返回退出码 1 |

## 报告怎么读

```
## Row metrics
| row | design y / gap-above / gap / h / margins | actual y / gap-above / gap / h / margins |
| 3   | y=633 gap↑=36 gap=[48,48] h=144 L/R=48/48 | y=609 gap↑=12 gap=[48,48] h=144 L/R=48/48 |
```

- `y`：该行首元素的纵向坐标
- `gap↑`：与上一行（或横幅）的垂直间距
- `gap`：同一行相邻元素之间的水平间距（`[48,48]` 表示两处都是 48）
- `h`：元素高度
- `L/R`：左/右屏幕边距
- `Per-rectangle diff` 里的 `dx/dy/dw/dh`：每个元素相对设计稿的位移/尺寸差

上面这行表示：网格第 1 行整体比设计稿**高了 24px**（`gap↑` 12 而设计是 36）。

## 标准工作流（可复用）

1. **取材**：拿到同分辨率（1920×1080）的设计稿 `design.png` 与车机截图 `shot.png`。
2. **比对**：`python3 uicheck.py design.png shot.png --out report.md`。
3. **读报告**：找 `dy/dw/dh != 0` 的元素、以及 `gap/gap↑/L/R` 与设计不一致的行。
4. **定位源码**：按下表把像素指标映射到代码。
5. **改码** → **编译** → **部署重截图** → **重跑第 2 步**，直到 delta 归零。

## 像素指标 → 源码 映射表（App Store）

| 报告中的指标 | 控制它的源码 |
|------|------|
| 卡片宽度 / 高度 | `item_app.xml`（根宽高）+ `GridLayoutManager` spanCount |
| 水平间距 `gap`（卡片↔卡片） | `CustomGridItemDecoration` 的 `horizontalSpacing` ← `RecommendationFragment` 的 `ITEM_SPACING_HORIZONTAL` |
| 垂直间距 `gap↑`（行↔行） | `CustomGridItemDecoration` 的 `verticalSpacing` ← `ITEM_SPACING_VERTICAL` |
| 左右边距 `L/R` | 容器 `LinearLayout` 的 `layout_marginHorizontal` |
| 横幅→网格间距（首行 `gap↑`） | `mAppRecyclerView` 的 `layout_marginTop` |
| 每行列数（spanCount） | `SplitScreenHelper.getGridSpanCount()` |
| 横幅尺寸 | `image_banner.xml` + `AppsAdapter.handleBannerLayout` 的 `BANNER_WIDTH_DP` |
| 卡片圆角/底色 | `item_app.xml` 的 `background`（`bg_item_app_rounded`） |

**注意**：`ITEM_SPACING_*` 在 Java 里是全屏/分屏共用的常量；`layout_margin*` 是
`layout-w*dp` 分屏特化的。改全屏时，`ITEM_SPACING_VERTICAL` 会同时影响分屏，
如需分屏不同值，要在 Java 里按 `SplitScreenHelper` 分支。

## 已知限制

- 检测「纯色背景上的元素」，通过水平膨胀把文字连成行、图标/按钮/卡片/面板按
  形状+填充率分类；分类是启发式的，极端样式下可能误标，但位置框仍准确。
- 匹配按空间就近（Manhattan 距离 < 40px）配对；若两图**结构不同**（行数不同、
  滚动位置不同、内容不同），尾部元素会错配或列入 unmatched——此时看
  `网格行指标` 和「差异叠加」更可靠。
- 检测阈（`--tol`）需匹配该页面的背景/卡片色差，默认 10 适用于深色卡片；若页面
  换肤或卡片色差更小，需调低 `--tol`。

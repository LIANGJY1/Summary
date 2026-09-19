# YD-393001 · 黑夜模式多媒体卡片进度条显示不明显
- **提交**：`794907dc` | 2026-07-31 | liqingqing | SystemUI | bugfix
- **缺陷库**：未关联单号（缺陷库 defs 为空）

## 问题
黑夜模式下多媒体卡片的歌曲播放进度条几乎看不出来，与白天模式观感/设计稿不一致。

## 根因分析
进度条使用 layer-list 资源 `seekbar_progress`（`@android:id/background` + `@android:id/progress` 双层结构），但工程只提供了白天版本（`res/drawable/`），没有 `drawable-night` 变体。黑夜模式下系统回退加载白天配色，进度层渐变色（浅色调）与夜间深色卡片背景对比度不足，进度条"隐身"。与 YD-392973（BTMusic 夜间图标泛白）同属"-night 资源缺失导致夜间回退"的模式，本提交是标准的正向修复：补齐夜间限定符资源。

## 关键代码修改
改动文件：新增 `application/SystemUI/src/main/res/drawable-night/seekbar_progress.xml`（唯一改动，51 行）
```xml
+++ application/SystemUI/src/main/res/drawable-night/seekbar_progress.xml
    <!-- 背景层 - 未播放部分 -->
    <item android:id="@android:id/background">
        <shape android:shape="rectangle">
            <solid android:color="#00DE7702" />   <!-- 全透明，不与夜色背景抢对比 -->
        </shape>
    </item>
    <!-- 进度层 -->
    <item android:id="@android:id/progress">
        <scale android:scaleWidth="100%" android:scaleGravity="left|center_vertical">
            <layer-list>
                <item>
                    <shape android:shape="rectangle">
                        <gradient android:type="linear" android:startColor="#00EEEEEE"
                            android:endColor="#33EEEEEE" android:angle="0" />
                    </shape>
                </item>
                <!-- 进度条细线：尾端全亮 #EEEEEE，保证可见性 -->
                <item android:top="78dp">
                    <shape android:shape="rectangle">
                        <gradient android:startColor="#00EEEEEE" android:endColor="#EEEEEE" android:angle="0" />
                    </shape>
                </item>
            </layer-list>
        </scale>
    </item>
```

## 为什么能修复
新增 `drawable-night/seekbar_progress.xml` 后，黑夜模式系统自动加载该夜间版本：背景层透明、进度层用 `#00EEEEEE→#33EEEEEE` 渐变并叠一条端点 `#EEEEEE` 的 2dp 细线，在深色背景上形成明确可见的进度轨迹；白天版本不受影响。零代码改动、零逻辑风险。注意：夜间版的层结构/尺寸必须与白天版逐层对齐（尺寸 364dp×80dp、细线 top=78dp），否则两模式视觉高度不一致。

## 复盘经验
- 自定义 drawable（layer-list/selector）同样受资源限定符回退机制影响，新建资源时就应同步评估是否需要 `-night` 变体，别等走查。
- 渐变进度条的可见性由"端点不透明度"决定：主体低透明度渐变 + 尾端全亮细线是可复用的夜间进度条配方。
- 排查"某控件夜间看不清"的固定路径：查其 drawable 引用 → `ls drawable-night/` → 缺失即回退白天资源。

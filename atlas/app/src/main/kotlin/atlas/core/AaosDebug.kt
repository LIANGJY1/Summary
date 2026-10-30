package atlas.core

import java.io.File

/** AAOS 设备调试预设与 adb 命令环境。 */
object AaosDebug {
    data class Preset(
        val id: String,
        val label: String,
        val group: String,
        val description: String,
        val command: String,
        /** 验收判定（改造前/改造后各一行）：非空的预设会在 UI 里渲染成详细卡片而非紧凑按钮。 */
        val expectation: String? = null,
    )

    val presets = listOf(
        Preset("device_list", "列出设备", "设备概况", "列出 adb 可见设备和连接状态。", "adb devices -l"),
        Preset("build_fingerprint", "系统版本", "设备概况", "读取设备构建指纹。", "adb shell getprop ro.build.fingerprint"),
        Preset("service_list", "系统服务", "系统状态", "查看 Binder 服务列表。", "adb shell service list"),
        Preset(
            "init_boot_chain", "Init 启动链检查", "系统状态",
            "只读采集设备版本、当前 PID 1、init 文件和启动阶段信息，并附中文解读。",
            """adb shell '
echo "=== 设备版本：确认实机运行的是哪版系统 ==="
getprop ro.build.fingerprint
getprop ro.build.version.release
echo "=== 当前 PID 1：系统此刻正在运行的程序 ==="
printf "命令行："
tr '\000' ' ' < /proc/1/cmdline
echo
printf "可执行文件："
readlink /proc/1/exe 2>&1
echo "=== init 文件：启动完成后当前还能看到哪些文件 ==="
if [ -e /init ]; then ls -lZ /init 2>&1; else echo "/init 当前不可见（启动后可能已切换根文件系统）"; fi
if [ -e /system/bin/init ]; then ls -lZ /system/bin/init 2>&1; else echo "/system/bin/init 当前不可见"; fi
echo "=== 启动阶段耗时（设备有记录时才会显示） ==="
printf "第一阶段："; getprop ro.boottime.init.first_stage
printf "SELinux 阶段："; getprop ro.boottime.init.selinux
echo "=== 怎么看 ==="
echo "上面的可执行文件只表示 PID 1 当前运行的位置；开机早期的 /init 随后会切换到 /system/bin/init。"
echo "因此本结果可确认当前系统版本与 PID 1 状态，不能单独证明 ramdisk 中 /init 的构建来源。"
'""",
        ),
        Preset("car_service", "车载服务", "系统状态", "查看 Car Service 状态；设备未启用时会显示服务不可用。", "adb shell dumpsys car_service"),
        Preset("activity_dump", "Activity 栈", "系统状态", "查看当前 Activity 和任务状态。", "adb shell dumpsys activity activities"),
        Preset("window_dump", "窗口状态", "系统状态", "查看 WindowManager 当前状态。", "adb shell dumpsys window"),
        Preset("recent_logcat", "最近日志", "日志", "读取最近 300 行日志；长时间追踪可在终端运行 adb logcat。", "adb logcat -d -t 300 -v time"),

        // ===== 状态栏透明改造（yadea_master 甲方案试水）验收命令 =====
        // 背景：Setting 由 windowIsTranslucent=true 改为 opaque + 不透明 windowBackground（顶部 64px 画
        // Launcher kanzi 裁剪条）。目标 = 状态栏观感不变的前提下，Launcher 被裁出 SF 合成、surface 可回收。
        Preset(
            "sbar_screen_base", "分辨率基数", "状态栏改造",
            "确认实车分辨率；它是所有内存/合成账目的基数（1280×720×4B ≈ 3.69MB/屏）。",
            "adb shell wm size",
            expectation = "任何时候: Override size 应为 1280x720\n若不同: 本组预设里的 3.69MB/7~11MB 等数字按像素数等比缩放",
        ),
        Preset(
            "sbar_setting_window", "Setting 窗口几何", "状态栏改造",
            "看 mFrame（窗口占的区域）与 mContentInsets（内容内缩）：窗口占满全屏、内容下移 64px。",
            """adb shell "dumpsys window windows" | grep -A 25 "com.yadea.setting"""",
            expectation = "改造前/后均应为: mFrame=[0,0][1280,720]（占满全屏）\nmContentInsets 或 mContentFrame top=64（fitsSystemWindows 生效）\n异常: 若 mFrame 顶到 64 以下说明窗口被限制在栏间（drawsSystemBarBackgrounds=false 行为）",
        ),
        Preset(
            "sbar_opaque_flip", "遮挡标志翻转", "状态栏改造",
            "occludesParent/isOpaque 是 translucent 开关落到 WMS 的那一句承诺；改造后应翻成 true，Launcher 才会被裁。",
            """adb shell "dumpsys window windows" | grep -E "occludesParent|isOpaque" | head -30""",
            expectation = "改造前: Setting 窗口 occludesParent=false（不遮挡→Launcher 保留在合成）\n改造后: occludesParent=true（遮挡→Launcher 被裁出合成列表）\n这一项翻不过来说明主题未生效（windowIsTranslucent 仍为 true）",
        ),
        Preset(
            "sbar_sf_layers", "SF 图层列表", "状态栏改造",
            "SurfaceFlinger 眼里的全部图层。核心验收：Setting 前台时不应再出现 launcher 层。",
            """adb shell "dumpsys SurfaceFlinger" --list""",
            expectation = "改造前: 列表含 com.yadea.launcher 相关层（每帧多合成一个全屏层）\n改造后: Setting 前台时无 launcher 层（★用户定的验收标准①）\n注意: 先在车机上打开 Setting 再跑，Launcher 在前台时看到 launcher 层是正常的",
        ),
        Preset(
            "sbar_launcher_layer", "Launcher 层合成方式", "状态栏改造",
            "Launcher 层的 compositionType：CLIENT=GPU 合成（真金白银）、DEVICE=HWC overlay（占平面但近免费）。",
            """adb shell "dumpsys SurfaceFlinger" | grep -B 2 -A 20 "launcher"""",
            expectation = "改造前: 若看到 launcher 层，关注其 compositionType（CLIENT 才是实锤开销）\n改造后: Setting 前台时此命令应搜不到 launcher 层\n附: 层详情里的 buffer 尺寸即 7~11MB surface 账的来源（双/三缓冲）",
        ),
        Preset(
            "sbar_setting_layer", "Setting 层详情", "状态栏改造",
            "改造主角自己的层：应为全屏 opaque 层，64px 条带像素就在它自己的 buffer 里。",
            """adb shell "dumpsys SurfaceFlinger" | grep -B 2 -A 12 "com.yadea.setting"""",
            expectation = "改造后: 层 frame=[0 0 1280 720]，compositionType 正常\n附: 条带由 SystemUI bar 绘制；本层 windowBackground 透明（用户决策），顶部被 bar 覆盖、主体由内容自绘\n警惕: 透明像素处若露出即为黑（home 已被裁）——静止黑线看根布局 paddingTop 环带",
        ),
        Preset(
            "sbar_launcher_stop", "Launcher 生命周期", "状态栏改造",
            "opaque 窗口的第二条收益：Launcher 从 visible/PAUSED 变 stopped，surface 可回收、进程可冻结。",
            """adb shell "dumpsys activity activities" | grep -B 2 -A 12 "yadea.launcher" | head -40""",
            expectation = "改造前: translucent 窗口下面 Launcher 保持 visible（mObservedRotation 之类的活动状态）\n改造后: 应看到 stopped / onStop 状态\n若仍 visible: opaque 翻转未生效（对照「遮挡标志翻转」预设）",
        ),
        Preset(
            "sbar_launcher_mem", "Launcher 内存", "状态栏改造",
            "surface/EGL buffer 回收的验证点：Graphics 与 GL 相关行的变化（每窗 7~11MB）。",
            """adb shell "dumpsys meminfo com.yadea.launcher" | grep -E "Graphics|GL mtrack|EGL|TOTAL" -A 2""",
            expectation = "改造前: 记录一次作为基线（Graphics/GL mtrack 数值）\n改造后: Setting 前台时 Launcher 的 Graphics 内存应下降（surface 被回收）\n附: kanzi 若是独立 GL surface，改造前 Launcher 可能占双份",
        ),
        Preset(
            "sbar_launcher_gfx", "Launcher 帧产出", "状态栏改造",
            "gfxinfo 帧统计：验证被覆盖期间 kanzi 确实停止出帧（渲染侧无浪费）。",
            "adb shell dumpsys gfxinfo com.yadea.launcher",
            expectation = "改造前: 被覆盖期间也应无新帧（kanzi 已停渲，这是前提事实）\n改造后: 同样无帧 + 合成列表出局，渲染与合成双停\n异常: 若改造前此命令显示持续出帧，说明 kanzi 未冻结，成本比预估更大",
        ),
        Preset(
            "sbar_bar_window", "状态栏窗口", "状态栏改造",
            "SystemUI 状态栏窗口本体：TYPE_STATUS_BAR、64px 高、根视图透明只画图标。",
            """adb shell "dumpsys window windows" | grep -A 12 "StatusBar"""",
            expectation = "任何时候: 窗口类型 TYPE_STATUS_BAR，高度 64px（StatusBarActor 硬编码）\n乙′方案: 前台非 Launcher 时 bar 自绘桌面条带（strip_kanzi_top），回 Launcher 渐隐\n优先级: 仪表实心形态(1/5/6/7) > 桌面条带 > 透明；bar 不参与转场动画，条带始终钉死",
        ),
        Preset(
            "sbar_window_policy", "窗口策略", "状态栏改造",
            "窗口策略总览：状态栏高度等布局常量的另一处佐证。",
            "adb shell dumpsys window policy",
            expectation = "关注 StatusBar 高度相关字段（应与 64px 一致）\n用于排查条带 drawable 高度与 bar 实际高度不齐导致的 1px 错位",
        ),
        Preset(
            "sbar_strip_shot", "条带截屏取证", "状态栏改造",
            "截一张整屏图到本机，用于顶部 64px 条带与 main_kanzi_view.png 顶部裁剪的逐像素 diff。",
            "adb exec-out screencap -p > ~/setting_strip.png && echo 已保存 ~/setting_strip.png",
            expectation = "分别截 改造前/改造后 各一张（改名保存）\n判定: 两图的顶部 64px 条带区域逐像素一致（★验收标准②，视觉不回退）\n本地 diff 可用 python PIL: Image.open(a).crop((0,0,1280,64)) 对比",
        ),
        Preset(
            "sbar_perfetto", "Perfetto 30s", "状态栏改造",
            "30 秒 trace（sched + SF 图层 + 帧时间线），抓完自动 pull 到本机，用 ui.perfetto.dev 打开。",
            """adb shell "cat > /data/local/tmp/pf_setting.txt <<'EOF'
buffers { size_kb: 65536 }
data_sources { config { name: 'linux.ftrace' ftrace_config { ftrace_events: 'sched_switch' 'sched_wakeup' 'cpu_frequency' 'cpu_idle' } } }
data_sources { config { name: 'android.surfaceflinger.layers' } }
data_sources { config { name: 'android.surfaceflinger.frame_timeline' } }
duration_ms: 30000
EOF" && adb shell "perfetto --txt -c /data/local/tmp/pf_setting.txt -o /data/misc/perfetto-traces/setting_30s.pftrace" && adb pull /data/misc/perfetto-traces/setting_30s.pftrace ~/setting_30s.pftrace && echo 已保存 ~/setting_30s.pftrace""",
            expectation = "操作: 先在车机打开 Setting，跑本命令，期间滚动 Setting 列表 10 秒以上\n改造前: SF 每帧合成包含 Launcher 层（Layers 轨道可见）\n改造后: 合成轨道里只有 Setting + StatusBar，且 Launcher 进程无渲染活动\n命令会阻塞 30s 属正常（配置 duration_ms=30000）",
        ),
        Preset(
            "sbar_window_anim", "窗口动画观察", "状态栏改造",
            "在应用打开/关闭的转场瞬间执行，抓正在播放的 surface 动画及其类型（translate 会带动烤进 windowBackground 的条带飞行）。",
            """adb shell "dumpsys window animator" | head -150""",
            expectation = "translate 类动画 + 甲方案 = 条带随窗口飞行（改 fade 或走乙方案）\nalpha/fade 或无动画 = 甲方案安全（fade 对像素一致的条带完全隐形）\n附: Setting 主题已全 @null 动画；Vlog 有 slide_in_bottom（styles.xml:73），Phase 2 迁移前必须先改动画\n抓转场要手快：动画 500ms，可在运行后立刻点开应用",
        ),
        Preset(
            "sbar_accept_all", "一键验收", "状态栏改造",
            "按验收顺序串联全部关键检查，中文注释分段输出；改造后逐项对照判定行。",
            """adb shell '
echo "=== ① 分辨率基数（应为 1280x720）==="
wm size
echo "=== ② SF 图层列表：Setting 前台时不应出现 launcher 层（验收标准①）==="
dumpsys SurfaceFlinger --list | grep -i "yadea" || echo "（未发现 yadea 层，确认应用已打开）"
echo "=== ③ Setting 窗口遮挡标志（改造后 occludesParent=true）==="
dumpsys window windows | grep -m 3 -E "occludesParent|isOpaque"
echo "=== ④ Launcher 生命周期（改造后应见 stopped）==="
dumpsys activity activities | grep -B 1 -A 6 "yadea.launcher" | head -20
echo "=== ⑤ Launcher Graphics 内存（对比基线）==="
dumpsys meminfo com.yadea.launcher | grep -E "Graphics|GL mtrack|TOTAL" | head -6
echo "=== 判定 ==="
echo "② 无 launcher 层 + ③ occludesParent=true + ④ stopped = 验收通过"
echo "若 ② 仍见 launcher 层：确认 Setting 在前台且 windowIsTranslucent=false 已编译进包"
'""",
            expectation = "改造前跑一次存基线，改造后再跑一次对照\n通过 = ② 无 launcher 层 + ③ true + ④ stopped\n视觉验收（条带像素 diff）请另跑「条带截屏取证」预设",
        ),
    )

    /** adb 自动读取 ANDROID_SERIAL；PATH 预置 SDK platform-tools 目录。 */
    fun commandEnvironment(serial: String?, adbPath: String, existingPath: String): Map<String, String> = buildMap {
        if (!serial.isNullOrBlank()) put("ANDROID_SERIAL", serial)
        put("PATH", "${File(adbPath).parent}:$existingPath")
    }
}

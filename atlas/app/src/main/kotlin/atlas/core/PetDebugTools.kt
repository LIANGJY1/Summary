package atlas.core

/** 萌宠调试命令目录：纯数据与命令构造，UI 和进程执行均不放在这里。 */
object PetDebugTools {

    const val DEBUG_KEY = "pet_debug_event"
    const val LAUNCHER_PACKAGE = "com.yadea.launcher"
    const val IPC_COMPONENT = "com.yadea.petipctest/.AdbReceiver"
    const val IPC_SEND_ACTION = "com.yadea.petipctest.SEND"
    const val IPC_CONNECT_ACTION = "com.yadea.petipctest.CONNECT"
    const val LAUNCHER_ROOT = "/home/liang/Project/Reachauto/YaDi/yadea_master"
    const val LAUNCHER_APK = "$LAUNCHER_ROOT/application/Launcher/build/outputs/apk/debug/NsrLauncher.apk"
    const val IPC_ROOT = "/home/liang/Project/Reachauto/YaDi/PetIpcTest"
    const val IPC_APK = "$IPC_ROOT/app/build/outputs/apk/debug/app-debug.apk"

    data class Command(
        val label: String,
        val args: List<String>,
        val workingDirectory: String? = null,
        val timeoutMs: Long = 30_000L,
    )

    sealed interface Step {
        data class Run(val command: Command) : Step
        data class Wait(val millis: Long) : Step
        data class Note(val text: String) : Step
    }

    data class QuickAction(
        val id: String,
        val label: String,
        val group: String,
        val sequence: String = "",
        val confirmation: String? = null,
    )

    data class Scenario(
        val number: Int,
        val stage: String,
        val name: String,
        val sequence: String,
        val expected: String,
        val limitation: String? = null,
    )

    data class RunState(
        val target: String? = null,
        val running: Boolean = false,
        val step: Int = 0,
        val totalSteps: Int = 0,
        val lines: List<String> = emptyList(),
        val exitCode: Int? = null,
    )

    fun settingsCommand(adbPath: String, serial: String, payload: String) = Command(
        label = "注入 $payload",
        // adb 会把 shell 后的 argv 再拼成远端命令；引号字符必须成为参数内容，双词 payload 才不会被截断。
        args = adb(adbPath, serial, "shell", "settings", "put", "global", DEBUG_KEY, "\"$payload\""),
    )

    fun ipcCommand(adbPath: String, serial: String, event: Int) = Command(
        label = "IPC 事件 $event",
        args = adb(
            adbPath, serial, "shell", "am", "broadcast",
            "-n", IPC_COMPONENT, "-a", IPC_SEND_ACTION, "--ei", "event", event.toString(),
        ),
    )

    fun connectIpcCommand(adbPath: String, serial: String) = Command(
        label = "连接 PetIpcTest",
        args = adb(adbPath, serial, "shell", "am", "broadcast", "-n", IPC_COMPONENT, "-a", IPC_CONNECT_ACTION),
    )

    fun petLogcatCommand(adbPath: String, serial: String) = Command(
        label = "萌宠日志",
        args = adb(
            adbPath, serial, "logcat", "-v", "time", "-s",
            "Launcher_PetDebugInject", "Launcher_PetStateMachine", "Launcher_PetSources", "PetIpcTest",
        ),
    )

    private fun adb(adbPath: String, serial: String, vararg args: String): List<String> =
        listOf(adbPath, "-s", serial) + args

    val quickActions: List<QuickAction> = buildList {
        add(QuickAction("fresh_on", "干净重启", "环境", "fresh_on", "将强制停止并重新启动 Launcher，当前动作会中断。"))
        add(QuickAction("clear_logcat", "清空日志", "环境"))
        add(QuickAction("connect_ipc", "连接 IPC 测试端", "环境"))
        add(QuickAction("build_launcher", "构建 Launcher", "构建安装"))
        add(QuickAction("install_launcher", "安装 Launcher", "构建安装", confirmation = "将覆盖在线设备上的 Launcher 调试包。"))
        add(QuickAction("build_ipc", "构建 PetIpcTest", "构建安装"))
        add(QuickAction("install_ipc", "安装 PetIpcTest", "构建安装", confirmation = "将覆盖在线设备上的 PetIpcTest。"))
        listOf(
            "switch_on" to "开关开", "switch_off" to "开关关", "ivi_ready" to "车机就绪",
            "render_on" to "回前台", "render_off" to "切后台", "clicked" to "点击",
            "music_on" to "音乐起播", "music_off" to "音乐停播", "voice_on" to "语音唤醒",
            "voice_off" to "语音结束", "long_idle" to "长期未登录", "battery_low" to "低电量",
            "battery_ok" to "电量恢复", "plug" to "插枪", "unplug" to "拔枪",
            "weather_none" to "无有效天气", "weather_rain" to "下雨", "weather_snow" to "下雪",
            "play_start" to "动作开始拍", "play_done" to "动作完成拍", "timer_idle" to "常驻定时器",
            "timer_watchdog" to "看门狗定时器",
        ).forEach { (payload, label) -> add(QuickAction(payload, label, "事件注入", "pet $payload")) }
        listOf(
            0 to "常驻", 1 to "出场", 2 to "随机 1", 3 to "随机 2", 4 to "随机 3",
            5 to "点击 1", 6 to "点击 2", 11 to "长期未登录", 12 to "雨", 13 to "雪",
            14 to "生日", 70 to "低电量", 80 to "充电", 90 to "音乐", 100 to "语音",
        ).forEach { (code, label) ->
            val id = if (code == 1) "send_failed_opening" else "send_failed_$code"
            add(QuickAction(id, "$label 发送失败", "发送失败", "pet send_failed $code"))
        }
        add(QuickAction("birthday_ipc", "生日 IPC", "推荐 IPC", "ipc 14"))
        listOf(0 to "取消节日", 1 to "春节", 2 to "端午", 3 to "国庆", 4 to "元旦", 5 to "中秋").forEach { (event, label) ->
            add(QuickAction(if (event == 1) "festival_spring_ipc" else "festival_${event}_ipc", "$label IPC", "推荐 IPC", "ipc $event"))
        }
        add(QuickAction("birthday_debug", "生日直注", "备用直注", "pet birthday"))
        listOf(0 to "取消节日", 1 to "春节", 2 to "端午", 3 to "国庆", 4 to "元旦", 5 to "中秋").forEach { (event, label) ->
            add(QuickAction("festival_debug_$event", "$label 直注", "备用直注", "pet festival $event"))
        }
        add(QuickAction("media_play", "媒体播放", "媒体真值", "media_play"))
        add(QuickAction("media_pause", "媒体暂停", "媒体真值", "media_pause"))
    }.distinctBy { it.id }

    fun quickActionSteps(action: QuickAction, adbPath: String, serial: String): List<Step> = when (action.id) {
        "clear_logcat" -> listOf(Step.Run(Command("清空日志", adb(adbPath, serial, "logcat", "-c"))))
        "connect_ipc" -> listOf(Step.Run(connectIpcCommand(adbPath, serial)))
        "build_launcher" -> listOf(Step.Run(Command(
            "构建 Launcher", listOf("bash", "./gradlew", ":application:Launcher:assembleDebug", "--offline"),
            LAUNCHER_ROOT, 10 * 60_000L,
        )))
        "install_launcher" -> listOf(Step.Run(Command(
            "安装 Launcher", adb(adbPath, serial, "install", "-r", LAUNCHER_APK), timeoutMs = 5 * 60_000L,
        )))
        "build_ipc" -> listOf(Step.Run(Command(
            "构建 PetIpcTest", listOf("bash", "./gradlew", ":app:assembleDebug", "--offline"),
            IPC_ROOT, 5 * 60_000L,
        )))
        "install_ipc" -> listOf(Step.Run(Command(
            "安装 PetIpcTest", adb(adbPath, serial, "install", "-r", IPC_APK), timeoutMs = 2 * 60_000L,
        )))
        else -> parseSequence(action.sequence, adbPath, serial)
    }

    fun scenarioSteps(scenario: Scenario, adbPath: String, serial: String): List<Step> =
        parseSequence(scenario.sequence, adbPath, serial)

    private fun parseSequence(sequence: String, adbPath: String, serial: String): List<Step> = buildList {
        sequence.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            when {
                token == "fresh_on" -> {
                    add(Step.Run(Command("清空残留注入", adb(adbPath, serial, "shell", "settings", "delete", "global", DEBUG_KEY))))
                    add(Step.Run(Command("停止 Launcher", adb(adbPath, serial, "shell", "am", "force-stop", LAUNCHER_PACKAGE))))
                    add(Step.Run(Command(
                        "启动 HOME Launcher",
                        adb(adbPath, serial, "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"),
                    )))
                    add(Step.Wait(3_000))
                }
                token.startsWith("pet ") -> {
                    add(Step.Run(settingsCommand(adbPath, serial, token.removePrefix("pet "))))
                    add(Step.Wait(300))
                }
                token.startsWith("ipc ") -> {
                    add(Step.Run(ipcCommand(adbPath, serial, token.removePrefix("ipc ").toInt())))
                    add(Step.Wait(300))
                }
                token == "media_play" || token == "media_pause" -> {
                    val action = token.removePrefix("media_")
                    add(Step.Run(Command("媒体 $action", adb(adbPath, serial, "shell", "cmd", "media_session", "dispatch", action))))
                    add(Step.Wait(1_000))
                }
                else -> add(Step.Note("未识别步骤：$token"))
            }
        }
    }

    val scenarios: List<Scenario> by lazy {
        scenarioRows.trimIndent().lineSequence()
            .filter { it.isNotBlank() }
            .map { row ->
                val c = row.split('|', limit = 7)
                Scenario(c[0].toInt(), c[1], c[2], c[3], c[4], c.getOrNull(5)?.ifBlank { null })
            }.toList()
    }

    private val scenarioRows = """
1|阶段 1 骨架与开关|first boot default on -> show only, wait ivi ready|fresh_on|只显示，未收到就绪沿时不播出场。|
2|阶段 1 骨架与开关|switch off at boot -> hide|fresh_on; pet switch_off|当前包近似为先显示后隐藏。|SWITCH_FORCE_ON=true 阻断精确 Init(false)，精确语义由 JVM case 锁定。
3|阶段 1 骨架与开关|switch off while running -> hide and clear|fresh_on; pet switch_off|隐藏并清空动作、定时器和挂起状态。|
4|阶段 1 骨架与开关|switch back on -> resident directly, no opening replay|fresh_on; pet switch_off; pet switch_on|重新显示并直接常驻，不补出场。|
5|阶段 1 骨架与开关|send failed -> retry up to limit|fresh_on; pet ivi_ready; pet send_failed 1; pet send_failed 1|出场初发后重试 2 次，不隐藏。|
6|阶段 1 骨架与开关|send failed beyond limit -> hide fallback|fresh_on; pet ivi_ready; pet send_failed 1; pet send_failed 1; pet send_failed 1|第 3 次失败后隐藏。|
7|阶段 2 出场与常驻|ivi ready foreground unplugged -> opening|fresh_on; pet ivi_ready|播出场并挂看门狗。|
8|阶段 2 出场与常驻|sound plays at dispatch (temporary immediate mode)|fresh_on; pet ivi_ready|出场下发同时播出场音效。|
9|阶段 2 出场与常驻|opening completed -> stop sound, resident loop starts|fresh_on; pet ivi_ready; pet play_done|停音效并进入常驻。|
10|阶段 2 出场与常驻|opening completed -> resident loop starts|fresh_on; pet ivi_ready; pet play_done|取消看门狗并进入常驻。|
11|阶段 2 出场与常驻|second ivi ready in same process -> ignored|fresh_on; pet ivi_ready; pet play_done; pet ivi_ready|第二个就绪沿不重播出场。|
12|阶段 2 出场与常驻|ivi ready while background -> opening missed, no replay after render start|fresh_on; pet render_off; pet ivi_ready; pet render_on|后台错过出场，回前台直接常驻。|
13|阶段 2 出场与常驻|ivi ready while plugged -> opening missed, charging directly|fresh_on; pet plug; pet ivi_ready; pet unplug|直接充电，拔枪回常驻，无出场。|
14|阶段 2 出场与常驻|render stop during opening -> terminate, resident on render start|fresh_on; pet ivi_ready; pet play_start; pet render_off; pet render_on|后台终止出场，回前台常驻。|
15|阶段 2 出场与常驻|duplicate render stop -> ignored|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet render_off|重复后台沿忽略。|
16|阶段 2 出场与常驻|idle timer fires -> next random action with watchdog|fresh_on; pet ivi_ready; pet play_done; pet timer_idle|抽随机动作并挂看门狗。|
17|阶段 2 出场与常驻|random completed -> back to resident with new timer|fresh_on; pet ivi_ready; pet play_done; pet timer_idle; pet play_done|随机完成回常驻并重新计时。|
18|阶段 2 出场与常驻|render stop during loop -> suspend timers, render start -> resume|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet render_on|后台撤计时，回前台常驻。|
19|阶段 2 出场与常驻|watchdog timeout without callback -> reset to resident|fresh_on; pet ivi_ready; pet play_done; pet timer_idle; pet timer_watchdog|看门狗复位为常驻。|
20|阶段 3 点击|click during resident -> click action with sound|fresh_on; pet ivi_ready; pet play_done; pet clicked|播放点击动作和音效。|
21|阶段 3 点击|click completed -> back to resident loop|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet play_start; pet play_done|点击完成停音效并回常驻。|
22|阶段 3 点击|click during opening -> rejected|fresh_on; pet ivi_ready; pet clicked|出场保持，不下发点击。|
23|阶段 3 点击|click while battery low -> ignored|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet clicked; pet battery_ok; pet clicked|低电量点击拦截，恢复后放行。|
24|阶段 3 点击|click while background -> ignored|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet clicked; pet render_on|后台点击不执行。|
25|阶段 3 点击|click during click action -> ignored|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet clicked|第二次点击忽略。|
26|阶段 3 点击|switch off clears loop, switch on re-enters resident|fresh_on; pet render_off; pet ivi_ready; pet render_on; pet switch_off; pet switch_on|重开直接常驻，不补出场。|
27|阶段 4 音乐|music start during resident -> dance, idle suspended|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet timer_idle|舞动并撤常驻计时，不播随机。|
28|阶段 4 音乐|music stop -> back to resident loop|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet music_off|发 91 并回常驻。|
29|阶段 4 音乐|music during opening -> deferred until opening completes|fresh_on; pet ivi_ready; pet music_on; pet play_done|出场完成后舞动。|
30|阶段 4 音乐|music playing at boot -> opening first, dance after completion|fresh_on; pet music_on; pet ivi_ready; pet play_done|先出场后舞动。|
31|阶段 4 音乐|music during click -> deferred until click completes|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet music_on; pet play_start; pet play_done|点击完成后补舞动。|
32|阶段 4 音乐|click during music -> pause semantics, dance resumes after click (2026-09-28)|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet clicked; pet play_start; pet play_done|点击完成后恢复舞动。|
33|阶段 4 音乐|click during music with lost resume edge -> fallback query resumes dance|fresh_on; media_play; pet ivi_ready; pet play_done; pet music_on; pet clicked; pet music_off; pet play_start; pet play_done|平台查询仍在播时兜底恢复。|必须先让网易云或蓝牙目标会话真实保持播放。
34|阶段 4 音乐|mirror corrected by fallback, later real pause edge ends dance (兜底后镜像校正锁定)|fresh_on; media_play; pet ivi_ready; pet play_done; pet music_on; pet clicked; pet music_off; pet play_start; pet play_done; media_pause; pet music_off|兜底恢复后真实暂停沿正常收舞。|必须有可响应媒体键的目标会话。
35|阶段 4 音乐|click during music with lost resume edge and music really off -> fallback stays resident|fresh_on; media_pause; pet ivi_ready; pet play_done; pet music_on; pet clicked; pet music_off; pet play_start; pet play_done|平台未播放，完成后回常驻。|
36|阶段 4 音乐|music start while battery low -> rejected|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet music_on; pet music_off|低电量保持，音乐拒绝。|
37|阶段 4 音乐|music accepted after battery recovers on new playing edge|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet music_on; pet music_off; pet battery_ok; pet music_on|恢复后新起播沿放行。|
38|阶段 4 音乐|battery drops during music -> dance continues, low battery waits for stop|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet battery_low; pet music_off|停播后低电量落位。|
39|阶段 4 音乐|music start while background -> rebuild on render start|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet music_on; pet render_on|回前台按实时状态舞动。|
40|阶段 4 音乐|music stop while background -> resident on render start|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet render_off; pet music_off; pet render_on|后台停播发 91，回前台常驻。|
41|阶段 4 音乐|music during voice -> deferred until voice session ends|fresh_on; pet ivi_ready; pet play_done; pet voice_on; pet music_on; pet voice_off|会话结束后舞动。|
42|阶段 5 语音|voice wake during resident -> interact, idle suspended|fresh_on; pet ivi_ready; pet play_done; pet voice_on; pet timer_idle|进入语音并撤常驻计时。|
43|阶段 5 语音|voice end -> back to resident loop|fresh_on; pet ivi_ready; pet play_done; pet voice_on; pet voice_off|发 101 并回常驻。|
44|阶段 5 语音|voice during music -> pause semantics, dance resumes after session (2026-09-28)|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet voice_on; pet voice_off|会话结束恢复舞动。|
45|阶段 5 语音|birthday during music -> pause semantics, dance resumes after birthday|fresh_on; pet ivi_ready; pet play_done; pet music_on; ipc 14; pet play_done|生日完成恢复舞动。|
46|阶段 5 语音|rain during music -> pause semantics, dance resumes after rain|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet weather_rain; pet play_done|雨完成恢复舞动。|
47|阶段 5 语音|voice during click -> deferred until click completes|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet voice_on; pet play_start; pet play_done|点击完成后进入语音。|
48|阶段 5 语音|click during voice -> pause semantics, voice loop restarts after click (2026-09-28)|fresh_on; pet ivi_ready; pet play_done; pet voice_on; pet clicked; pet play_start; pet play_done|点击完成后重启语音。|
49|阶段 5 语音|voice end arrives while never started -> ignored|fresh_on; pet ivi_ready; pet voice_on; pet voice_off; pet play_done|迟沿取消语音意图。|
50|阶段 5 语音|voice wake while background -> rebuild on render start|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet voice_on; pet render_on|回前台进入语音。|
51|阶段 5 语音|voice during low battery -> pauses it, low battery not resumed after session|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet voice_on; pet voice_off|会话结束回常驻，不恢复低电量。|
52|阶段 5 语音|voice at boot before ivi ready -> opening first, interact after|fresh_on; pet voice_on; pet ivi_ready; pet play_done|先出场后语音。|
53|阶段 6 电量充电|battery below threshold during resident -> low battery, idle suspended|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet timer_idle|低电量并撤常驻计时。|
54|阶段 6 电量充电|battery recovers -> low battery ends, resident loop|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet battery_ok|发 71 并回常驻。|
55|阶段 6 电量充电|low battery during opening -> deferred until opening completes|fresh_on; pet ivi_ready; pet battery_low; pet play_done|出场完成后低电量落位。|
56|阶段 6 电量充电|low battery during click -> deferred until click completes|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet battery_low; pet play_start; pet play_done|点击完成后低电量落位。|
57|阶段 6 电量充电|low battery during voice -> deferred until voice ends|fresh_on; pet ivi_ready; pet play_done; pet voice_on; pet battery_low; pet voice_off|语音结束后低电量落位。|
58|阶段 6 电量充电|low battery while charging -> rejected|fresh_on; pet ivi_ready; pet play_done; pet plug; pet battery_low; pet timer_idle|充电保持，低电量拒绝。|
59|阶段 6 电量充电|low battery at boot before ivi ready -> opening first, low battery after|fresh_on; pet battery_low; pet ivi_ready; pet play_done|先出场后低电量。|
60|阶段 6 电量充电|low battery while background -> rebuild on render start|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet battery_low; pet render_on|回前台进入低电量。|
61|阶段 6 电量充电|plug in during resident -> charging, idle suspended|fresh_on; pet ivi_ready; pet play_done; pet plug; pet timer_idle|进入充电并撤计时。|
62|阶段 6 电量充电|plug out -> back to resident loop|fresh_on; pet ivi_ready; pet play_done; pet plug; pet unplug|发 81 并回常驻。|
63|阶段 6 电量充电|plug during opening -> deferred until opening completes|fresh_on; pet ivi_ready; pet plug; pet play_done|出场完成后充电。|
64|阶段 6 电量充电|plug during click -> preempts immediately|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet plug|充电独占点击。|
65|阶段 6 电量充电|plug during music -> preempts, music not resumed after unplug|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet plug; pet unplug|拔枪回常驻，不恢复舞动。|
66|阶段 6 电量充电|plug during voice -> preempts|fresh_on; pet ivi_ready; pet play_done; pet voice_on; pet plug|充电独占语音。|
67|阶段 6 电量充电|low battery then plug -> charging, low battery suppressed after unplug|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet plug; pet unplug; pet render_off; pet render_on|低电量不复活。|
68|阶段 6 电量充电|suppressed low battery re-arms on fresh battery cycle|fresh_on; pet ivi_ready; pet play_done; pet battery_low; pet plug; pet battery_ok; pet unplug; pet battery_low|新电量周期重新放行。|
69|阶段 6 电量充电|charging while background -> rebuild on render start|fresh_on; pet ivi_ready; pet play_done; pet render_off; pet plug; pet render_on|回前台进入充电。|
70|阶段 6 电量充电|click during charging -> rejected|fresh_on; pet ivi_ready; pet play_done; pet plug; pet clicked|充电保持，点击拒绝。|
71|阶段 6 电量充电|music during charging -> deferred until unplug|fresh_on; pet ivi_ready; pet play_done; pet plug; pet music_on; pet unplug|拔枪后舞动。|
72|阶段 6 电量充电|voice during charging -> rejected|fresh_on; pet ivi_ready; pet play_done; pet plug; pet voice_on; pet voice_off|充电保持，语音拒绝。|
73|机制锁定|click during idle window -> random timer suppressed until completion|fresh_on; pet ivi_ready; pet play_done; pet clicked; pet timer_idle; pet play_done|点击窗口不播随机。|
74|机制锁定|music suppressed by charging stays off after unplug across render cycle|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet plug; pet unplug; pet render_off; pet render_on|舞动不复活。|
75|机制锁定|music dances again on new playing edge after charging|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet plug; pet unplug; pet music_off; pet music_on|新起播沿重新舞动。|
76|机制锁定|delayed one shot held in pending slot plays after current completes|fresh_on; pet ivi_ready; pet clicked; pet play_done|正式矩阵会拒绝点击。|此 case 在 JVM 测试内临时覆盖裁决表，设备正式表不可精确复现。
77|机制锁定|music then voice then charging -> voice suppressed only, dance resumes after unplug|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet voice_on; pet plug; pet unplug; pet render_off; pet render_on|仅语音被抑制，音乐恢复。|
78|生日天气节日|birthday event after opening plays birthday action|fresh_on; pet ivi_ready; pet play_done; ipc 14|播放生日动作。|
79|生日天气节日|duplicate birthday signals each play (2026-09-28口径)|fresh_on; pet ivi_ready; pet play_done; ipc 14; pet play_done; ipc 14|两次生日均播放。|
80|生日天气节日|birthday audio is deferred by requirement decision|fresh_on; pet ivi_ready; pet play_done; ipc 14|不播生日音效。|
81|生日天气节日|switch off during birthday reports skipped|fresh_on; pet ivi_ready; pet play_done; ipc 14; pet switch_off|回报 SKIPPED 并隐藏。|
82|生日天气节日|birthday send retries exhausted reports skipped|fresh_on; pet ivi_ready; pet play_done; ipc 14; pet send_failed 14; pet send_failed 14; pet send_failed 14|耗尽后回报 SKIPPED 并隐藏。|
83|生日天气节日|birthday during opening waits until opening completes|fresh_on; pet ivi_ready; ipc 14; pet play_done|出场完成后生日播放。|
84|生日天气节日|birthday during charging returns skipped|fresh_on; pet ivi_ready; pet play_done; pet plug; ipc 14|充电中回报 SKIPPED。|
85|生日天气节日|rain weather event after opening plays rain action|fresh_on; pet ivi_ready; pet play_done; pet weather_rain|播放雨动作。|
86|生日天气节日|weather uses first valid rain or snow event once per process|fresh_on; pet ivi_ready; pet play_done; pet weather_none; pet weather_rain; pet play_done; pet weather_snow|雨执行，后续雪忽略。|
87|生日天气节日|festival signal applies skin without replacing current action|fresh_on; pet ivi_ready; pet play_done; ipc 1|叠加春节皮肤。|
88|生日天气节日|festival applied reports result and plays sound once|fresh_on; pet ivi_ready; pet play_done; ipc 1; ipc 1|首次播节日音效，两次均回报。|
89|生日天气节日|no festival removes applied skin|fresh_on; pet ivi_ready; ipc 1; ipc 0|撤销节日皮肤。|
90|生日天气节日|switch off during music -> music end code then hide|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet switch_off|先发 91 再隐藏。|
91|SRS_007 长期未登录|long idle signal before ivi ready plays after opening|fresh_on; pet long_idle; pet ivi_ready; pet play_done|先出场后长期未登录。|
92|SRS_007 长期未登录|long idle signal during opening waits until opening completes|fresh_on; pet ivi_ready; pet long_idle; pet play_done|出场完成后补播。|
93|SRS_007 长期未登录|long idle signal with resident active pauses resident and plays|fresh_on; pet ivi_ready; pet play_done; pet long_idle|暂停常驻并播放。|
94|SRS_007 长期未登录|long idle is one shot without sound and returns to resident|fresh_on; pet ivi_ready; pet play_done; pet long_idle; pet play_done|无音效，完成回常驻。|
95|SRS_007 长期未登录|duplicate long idle signals fire once|fresh_on; pet ivi_ready; pet play_done; pet long_idle; pet long_idle; pet play_done|重复信号只执行一次。|
96|SRS_007 长期未登录|long idle rejected while charging|fresh_on; pet ivi_ready; pet play_done; pet plug; pet long_idle|充电保持，长期未登录拒绝。|
97|SRS_007 长期未登录|long idle rejected while music dancing|fresh_on; pet ivi_ready; pet play_done; pet music_on; pet long_idle|舞动保持，长期未登录拒绝。|
98|SRS_007 长期未登录|long idle ignored when switch off|fresh_on; pet switch_off; pet long_idle|开关关闭时忽略。|
"""
}

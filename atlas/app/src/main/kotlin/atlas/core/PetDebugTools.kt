package atlas.core

import java.io.File
import java.nio.file.Files

/** 萌宠调试命令目录：纯数据与命令构造，UI 和进程执行均不放在这里。 */
object PetDebugTools {

    const val DEBUG_KEY = "pet_debug_event"
    const val LAUNCHER_PACKAGE = "com.yadea.launcher"
    const val IPC_COMPONENT = "com.yadea.petipctest/.AdbReceiver"
    const val IPC_SEND_ACTION = "com.yadea.petipctest.SEND"
    const val IPC_CONNECT_ACTION = "com.yadea.petipctest.CONNECT"
    private const val WEATHER_PACKAGE = "com.android.ext.autoweather"
    private const val WEATHER_BACKDOOR_COMPONENT = "$WEATHER_PACKAGE/.ui.WeatherBackdoorActivity"
    private const val WEATHER_TEMP_FILE = "/data/local/tmp/weather_backdoor.json"
    private const val WEATHER_PRIVATE_FILE = "files/weather_backdoor.json"
    const val LAUNCHER_ROOT = "/home/liang/Project/Reachauto/YaDi/yadea_master"
    const val LAUNCHER_APK = "$LAUNCHER_ROOT/application/Launcher/build/outputs/apk/debug/NsrLauncher.apk"
    const val IPC_ROOT = "/home/liang/Project/Reachauto/YaDi/PetIpcTest"
    const val IPC_APK = "$IPC_ROOT/app/build/outputs/apk/debug/app-debug.apk"

    data class Command(
        val label: String,
        val args: List<String>,
        val workingDirectory: String? = null,
        val timeoutMs: Long = 30_000L,
        val explanation: String = label,
    )

    sealed interface Step {
        data class Run(val command: Command) : Step
        data class Wait(val millis: Long, val explanation: String) : Step
        data class Note(val text: String) : Step
        data class CleanupLocalFile(val file: File) : Step
    }

    data class QuickAction(
        val id: String,
        val label: String,
        val group: String,
        val sequence: String = "",
        val confirmation: String? = null,
    )

    data class WeatherCase(
        val id: String,
        val label: String,
        val weatherCode: String,
        val weatherName: String,
        val expected: String,
    )

    val weatherServiceCases = listOf(
        WeatherCase("heavy_rain", "大雨（10）", "10", "大雨", "天气 SDK 推送雨天；当前提供的 JSON 样本原本即使用 weather_code=10。"),
        WeatherCase("rain", "雨（301）", "301", "雨", "天气 SDK 推送雨天；Launcher 在本进程周期内触发一次雨天动作。"),
        WeatherCase("sleet", "雨夹雪（6）", "6", "雨夹雪", "雨夹雪按雨天映射，触发一次雨天动作。"),
        WeatherCase("light_snow", "小雪（14）", "14", "小雪", "小雪按雪天映射，触发一次雪天动作。"),
        WeatherCase("snow", "雪（302）", "302", "雪", "天气 SDK 推送雪天；Launcher 在本进程周期内触发一次雪天动作。"),
        WeatherCase("snowstorm", "暴雪（17）", "17", "暴雪", "暴雪按雪天映射，触发一次雪天动作。"),
        WeatherCase("clear", "晴（0）", "0", "晴", "非雨雪天气不触发天气动作，保持当前常驻状态。"),
        WeatherCase("unknown", "未知（99）", "99", "无", "未知/无效天气不触发动作，也不消费本进程周期的首次有效天气机会。"),
    )

    data class Scenario(
        val number: Int,
        val stage: String,
        val name: String,
        val sequence: String,
        val expected: String,
        val limitation: String? = null,
        val titleZh: String,
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
        add(QuickAction(
            "weather_clear_data", "清理天气应用数据", "天气服务",
            confirmation = "将清除设备上 com.android.ext.autoweather 的应用数据和缓存。",
        ))
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
        "weather_clear_data" -> listOf(Step.Run(clearWeatherDataCommand(adbPath, serial)))
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

    /** Copy a prepared provider JSON while changing only data.live's two weather fields. */
    fun prepareWeatherCaseFile(templatePath: String, weatherCase: WeatherCase): File {
        val template = File(templatePath)
        require(template.isFile && template.canRead()) { "请选择可读取的天气 JSON 文件" }
        val json = template.readText(Charsets.UTF_8)
        val live = extractObject(json, "live")
        val updatedLive = replaceJsonStringField(live, "weather_code", weatherCase.weatherCode)
        val finalLive = replaceJsonStringField(updatedLive, "weather_CN", weatherCase.weatherName)
        val start = json.indexOf(live)
        require(start >= 0) { "无法定位 JSON 中的 live 天气对象" }
        val updated = json.replaceRange(start, start + live.length, finalLive)
        val output = Files.createTempFile("atlas-weather-${weatherCase.id}-", ".json").toFile()
        output.writeText(updated, Charsets.UTF_8)
        return output
    }

    /** Replays tq.bat's app-private JSON backdoor flow for the actual weather service path. */
    fun weatherServiceSteps(adbPath: String, serial: String, localJson: File): List<Step> = listOf(
        Step.Run(Command(
            "停止天气 App",
            adb(adbPath, serial, "shell", "am", "force-stop", WEATHER_PACKAGE),
            explanation = "关闭天气进程，避免旧 Activity 或缓存干扰本次后门注入。",
        )),
        Step.Run(Command("清理设备临时文件", adb(adbPath, serial, "shell", "rm", "-f", WEATHER_TEMP_FILE))),
        Step.Run(Command(
            "推送天气 JSON",
            adb(adbPath, serial, "push", localJson.absolutePath, WEATHER_TEMP_FILE),
            explanation = "把所选模板的雨雪 case 写入设备临时目录。",
        )),
        Step.Run(Command(
            "写入天气 App 私有目录",
            adb(
                adbPath, serial, "shell",
                "run-as $WEATHER_PACKAGE sh -c 'mkdir -p files && cat $WEATHER_TEMP_FILE > $WEATHER_PRIVATE_FILE'",
            ),
            explanation = "通过 run-as 将 JSON 写到天气 App 实际读取的 files/weather_backdoor.json。",
        )),
        Step.Run(Command(
            "校验天气 JSON 已写入",
            adb(adbPath, serial, "shell", "run-as", WEATHER_PACKAGE, "ls", "-l", WEATHER_PRIVATE_FILE),
        )),
        Step.Run(Command(
            "启动天气后门 Activity",
            adb(adbPath, serial, "shell", "am", "start", "-S", "-n", WEATHER_BACKDOOR_COMPONENT),
            explanation = "强制创建 WeatherBackdoorActivity，让天气 App 重新读取并发布当前天气。",
        )),
        Step.Run(Command("清理设备临时文件", adb(adbPath, serial, "shell", "rm", "-f", WEATHER_TEMP_FILE))),
        Step.CleanupLocalFile(localJson),
    )

    fun clearWeatherDataCommand(adbPath: String, serial: String): Command = Command(
        "清理天气应用数据",
        adb(adbPath, serial, "shell", "pm", "clear", WEATHER_PACKAGE),
        explanation = "清除天气 App 本地数据及缓存。",
    )

    fun scenarioDescriptions(scenario: Scenario): List<String> = buildList {
        add("准备环境：清理旧注入并重新启动 Launcher")
        scenario.sequence.split(';').map(String::trim).filter(String::isNotEmpty).forEach { token ->
            when {
                token == "fresh_on" -> Unit
                token.startsWith("pet ") -> add(eventDescription(token.removePrefix("pet ")))
                token.startsWith("ipc ") -> add(ipcDescription(token.removePrefix("ipc ").toInt()))
                token == "media_play" -> add("通过 Android 媒体会话发送播放按键")
                token == "media_pause" -> add("通过 Android 媒体会话发送暂停按键")
                else -> add("无法识别的步骤：$token")
            }
        }
    }

    fun scenarioTechnicalCommands(scenario: Scenario, adbPath: String, serial: String): List<String> =
        scenarioSteps(scenario, adbPath, serial).mapNotNull { step ->
            when (step) {
                is Step.Run -> formatCommand(step.command.args)
                is Step.Wait -> "sleep ${step.millis}ms"
                is Step.Note -> null
                is Step.CleanupLocalFile -> null
            }
        }

    fun quickActionTechnicalCommands(action: QuickAction, adbPath: String, serial: String): List<String> =
        quickActionSteps(action, adbPath, serial).mapNotNull { step ->
            (step as? Step.Run)?.let { formatCommand(it.command.args) }
        }

    private fun formatCommand(args: List<String>): String = args.joinToString(" ") { arg ->
        if (arg.any(Char::isWhitespace)) "\"${arg.replace("\"", "\\\"")}\"" else arg
    }

    private fun extractObject(json: String, key: String): String {
        val keyMatch = Regex("\"${Regex.escape(key)}\"\\s*:").find(json)
            ?: error("天气 JSON 缺少 $key 对象")
        val open = json.indexOf('{', keyMatch.range.last + 1)
        require(open >= 0) { "$key 不是 JSON 对象" }
        var depth = 0
        var inString = false
        var escaped = false
        for (index in open until json.length) {
            val char = json[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return json.substring(open, index + 1)
                }
            }
        }
        error("$key 对象未闭合")
    }

    private fun replaceJsonStringField(jsonObject: String, key: String, value: String): String {
        val field = Regex("(\"${Regex.escape(key)}\"\\s*:\\s*)\"(?:\\\\.|[^\"\\\\])*\"")
        require(field.findAll(jsonObject).count() == 1) { "live 对象中需要且只能有一个 $key 字段" }
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
        return field.replace(jsonObject) { match -> "${match.groupValues[1]}\"$escaped\"" }
    }

    private fun parseSequence(sequence: String, adbPath: String, serial: String): List<Step> = buildList {
        sequence.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            when {
                token == "fresh_on" -> {
                    add(Step.Run(Command("清理上次注入", adb(adbPath, serial, "shell", "settings", "delete", "global", DEBUG_KEY), explanation = "删除上一次留下的调试事件，避免新场景被旧状态干扰。")))
                    add(Step.Run(Command("重新启动萌宠进程", adb(adbPath, serial, "shell", "am", "force-stop", LAUNCHER_PACKAGE), explanation = "结束 Launcher 进程，重置本进程内的一次性状态。")))
                    add(Step.Run(Command(
                        "打开车机桌面",
                        adb(adbPath, serial, "shell", "am", "start", "-W", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.HOME"),
                        explanation = "从 HOME 入口重新启动 Launcher，让场景从干净启动状态开始。",
                    )))
                    add(Step.Wait(3_000, "等待萌宠完成启动并进入可接收事件状态。"))
                }
                token.startsWith("pet ") -> {
                    val event = token.removePrefix("pet ")
                    add(Step.Run(settingsCommand(adbPath, serial, event).copy(explanation = eventDescription(event))))
                    add(Step.Wait(300, "留出事件处理时间，再发送下一步。"))
                }
                token.startsWith("ipc ") -> {
                    val event = token.removePrefix("ipc ").toInt()
                    add(Step.Run(ipcCommand(adbPath, serial, event).copy(explanation = ipcDescription(event))))
                    add(Step.Wait(300, "等待 PetIpcTest 广播被 Launcher 接收。"))
                }
                token == "media_play" || token == "media_pause" -> {
                    val playing = token == "media_play"
                    val label = if (playing) "通知媒体会话进入播放" else "通知媒体会话暂停"
                    add(Step.Run(Command(label, adb(adbPath, serial, "shell", "cmd", "media_session", "dispatch", if (playing) "play" else "pause"), explanation = "向 Android 媒体会话发送${if (playing) "播放" else "暂停"}按键；需要有可响应的媒体播放器。")))
                    add(Step.Wait(1_000, "等待媒体状态同步到萌宠事件监听。"))
                }
                else -> add(Step.Note("未识别步骤：$token"))
            }
        }
    }

    private fun eventDescription(event: String): String = when {
        event.startsWith("send_failed ") -> "模拟动作下发失败（动作码 ${event.substringAfter(' ')}），观察重试或回退。"
        event.startsWith("festival ") -> "设置节日皮肤类型 ${event.substringAfter(' ')}。"
        else -> when (event) {
            "switch_on" -> "打开萌宠开关。"
            "switch_off" -> "关闭萌宠开关，验证隐藏与状态清理。"
            "ivi_ready" -> "通知车机初始化完成，允许萌宠执行启动流程。"
            "render_on" -> "模拟萌宠回到前台。"
            "render_off" -> "模拟萌宠进入后台。"
            "clicked" -> "模拟用户点击萌宠。"
            "music_on" -> "通知萌宠音乐开始播放。"
            "music_off" -> "通知萌宠音乐停止播放。"
            "voice_on" -> "通知萌宠语音会话开始。"
            "voice_off" -> "通知萌宠语音会话结束。"
            "long_idle" -> "模拟长期未登录提醒到达。"
            "battery_low" -> "模拟电量低于阈值。"
            "battery_ok" -> "模拟电量恢复到阈值以上。"
            "plug" -> "模拟车辆接入充电枪。"
            "unplug" -> "模拟车辆拔出充电枪。"
            "weather_rain" -> "模拟天气变为下雨。"
            "weather_snow" -> "模拟天气变为下雪。"
            "weather_none" -> "模拟无有效天气信息。"
            "play_start" -> "通知状态机当前动作开始执行。"
            "play_done" -> "通知状态机当前动作已经完成，推进后续裁决。"
            "timer_idle" -> "触发常驻动作定时器。"
            "timer_watchdog" -> "触发出场看门狗超时检查。"
            "birthday" -> "模拟生日动作请求（直注通道）。"
            else -> "发送萌宠调试事件：$event。"
        }
    }

    private fun ipcDescription(event: Int): String = when (event) {
        14 -> "通过 PetIpcTest 正式广播链路发送生日事件。"
        0 -> "通过正式 IPC 清除节日状态。"
        else -> "通过 PetIpcTest 正式广播链路设置节日类型 $event。"
    }

    val scenarios: List<Scenario> by lazy { loadScenarios() }

    private fun loadScenarios(): List<Scenario> {
        fun decode(value: String) = value.replace("\\t", "\t").replace("\\n", "\n").replace("\\\\", "\\")
        val sourceRows = resourceLines("pet_scenarios.tsv")
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('	').map(::decode) }
        val titles = resourceLines("pet_scenario_zh.tsv")
            .filter { it.isNotBlank() }
            .associate { line ->
                val (number, title) = line.split('	', limit = 2)
                number.toInt() to title
            }
        require(sourceRows.size == 100 && titles.size == 100) { "萌宠场景资源必须完整包含 100 条" }
        return sourceRows.map { c ->
            require(c.size == 6) { "萌宠场景资源列数异常: ${c.firstOrNull()}" }
            val number = c[0].toInt()
            Scenario(
                number = number,
                stage = c[1],
                name = c[2],
                sequence = c[3],
                expected = c[4],
                limitation = c[5].ifBlank { null },
                titleZh = titles[number] ?: error("缺少场景 #$number 的中文标题"),
            )
        }.also { rows ->
            require(rows.map { it.number } == (1..100).toList()) { "萌宠场景编号/顺序必须为 1..100" }
        }
    }

    private fun resourceLines(name: String): List<String> =
        javaClass.classLoader.getResourceAsStream(name)?.bufferedReader(Charsets.UTF_8)?.use { it.readLines() }
            ?: error("缺少萌宠场景资源: $name")
}

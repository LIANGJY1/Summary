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
    )

    /** adb 自动读取 ANDROID_SERIAL；PATH 预置 SDK platform-tools 目录。 */
    fun commandEnvironment(serial: String?, adbPath: String, existingPath: String): Map<String, String> = buildMap {
        if (!serial.isNullOrBlank()) put("ANDROID_SERIAL", serial)
        put("PATH", "${File(adbPath).parent}:$existingPath")
    }
}

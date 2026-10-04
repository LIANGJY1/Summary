package atlas.android

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import atlas.core.Log
import atlas.platform.Platform
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 本机层配置与日志放应用私有目录；仓库同步层仍在 <库根>/atlas/config/settings.properties，
        // 与桌面端经 git 同一份（SettingsStore 双层机制原样复用）
        val configDir = File(filesDir, "atlas-config")
        Platform.init(applicationContext)
        Log.init(configDir)
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            Log.e("未捕获异常（thread=${t.name}）——应用崩溃", e)
        }
        Log.i(
            "应用启动(android) sdk=${Build.VERSION.SDK_INT} model=${Build.MODEL} " +
                "heapMax=${Runtime.getRuntime().maxMemory() / 1048576}MB configDir=${configDir.absolutePath}"
        )
        setContent { AtlasRoot(configDir) }
    }
}

package atlas.android

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import atlas.AppStore
import atlas.core.Log
import atlas.platform.Platform
import atlas.ui.Theme
import java.io.File

/** 建库页：对应桌面 SetupView，目录选择换成内置浏览器（桌面 JFileChooser 的手机等价物）。
 *  共享存储访问靠「所有文件访问」权限（个人侧载应用，无商店政策约束）。 */
@Composable
fun SetupScreen(store: AppStore) {
    val context = LocalContext.current
    var path by remember { mutableStateOf(store.settings.libraryPath) }
    var showBrowser by remember { mutableStateOf(false) }
    var hasAllFiles by remember { mutableStateOf(hasAllFilesAccess(context)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Atlas", fontWeight = FontWeight.Bold, fontSize = 28.sp)
        Text(
            "把一个 markdown 目录变成：可检索的知识库 + 闪卡复习 + 学习任务队列。\n" +
                "先把知识库同步到手机（git 客户端 clone、Syncthing 或 adb push 均可），再在这里选中库根目录。",
            fontSize = 14.sp, color = Theme.Muted, lineHeight = 22.sp,
        )
        store.bootError?.let {
            Text(it, color = Theme.BadRed, fontSize = 12.sp)
        }
        if (!hasAllFiles) {
            Text(
                "Atlas 需要读取共享存储上的知识库目录（建库、写回卡片与题目），请授予「所有文件访问权限」。",
                color = Theme.WarnOrange, fontSize = 13.sp, lineHeight = 19.sp,
            )
            Button(onClick = {
                // 厂商 ROM 兼容：优先带包名的应用页，失败再落通用开关页
                runCatching {
                    context.startActivity(
                        Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }.onFailure {
                    runCatching { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                        .onFailure { ex -> Log.e("跳转所有文件访问授权失败", ex); store.showToast("无法打开授权页：${ex.message}") }
                }
            }) { Text("去授权所有文件访问") }
        }
        OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("知识库目录（选一个 md 文件夹）") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showBrowser = true }) { Text("选择目录") }
            OutlinedButton(onClick = {
                if (!hasAllFilesAccess(context)) { store.showToast("请先授予所有文件访问权限"); return@OutlinedButton }
                try {
                    Log.i("Setup 请求打开库 path=$path")
                    if (File(path).isDirectory) store.openLibrary(path, rescanIfNeeded = true)
                    else { Log.w("Setup 打开失败：目录不存在 $path"); store.showToast("目录不存在") }
                } catch (e: Exception) {
                    Log.e("Setup 打开库异常 path=$path", e)
                    store.showToast("打开失败：${e.message}")
                }
            }) { Text("打开这个库") }
        }
        Text(
            "提示：Atlas 会在库里创建 atlas/ 目录存放卡片与题目（cards.md、questions.md、inbox、outbox），与桌面端共用同一份文件。",
            fontSize = 12.sp, color = Theme.Muted, lineHeight = 18.sp,
        )
    }

    if (showBrowser) {
        DirectoryBrowserDialog(
            initial = path.ifBlank { Platform.defaultLibraryPath },
            onDismiss = { showBrowser = false },
            onPicked = { picked ->
                path = picked
                showBrowser = false
                hasAllFiles = hasAllFilesAccess(context)
            },
        )
    }
}

/** API 30+ 用「所有文件访问」；API 29 走传统 WRITE_EXTERNAL_STORAGE（manifest 已声明） */
internal fun hasAllFilesAccess(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else ContextCompat_checkSelfPermission(context)

private fun ContextCompat_checkSelfPermission(context: Context): Boolean =
    androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
    ) == PackageManager.PERMISSION_GRANTED

/** 内置目录浏览器：MANAGE_EXTERNAL_STORAGE 授权后直接按 File 树浏览共享存储，选中库根。
 *  列表只出目录、隐藏点开头目录；顶部显示当前路径，底部「选中当前目录」完成选择。 */
@Composable
fun DirectoryBrowserDialog(initial: String, onDismiss: () -> Unit, onPicked: (String) -> Unit) {
    var current by remember {
        mutableStateOf(
            run {
                var f = File(initial)
                // 初始路径不存在时逐级上溯到最近的存在目录，避免空浏览器
                while (f.parentFile != null && !f.exists()) f = f.parentFile!!
                if (f.isDirectory) f else File("/sdcard")
            }
        )
    }
    val dirs = remember(current) {
        current.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            Modifier.fillMaxWidth().fillMaxHeight(0.72f),
            shape = MaterialTheme.shapes.medium,
            color = Theme.Panel,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择知识库目录", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Text(
                    current.absolutePath,
                    fontSize = 12.sp, color = Theme.Info,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (dirs.isEmpty() && current.listFiles() == null) {
                    Text(
                        "无法读取该目录：若未授权「所有文件访问」，请回建库页先授权。",
                        color = Theme.WarnOrange, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    if (current.parentFile != null) {
                        item {
                            Text(
                                "⬆ 上一级",
                                Modifier.fillMaxWidth().clickable { current = current.parentFile!! }.padding(vertical = 11.dp),
                                color = Theme.Accent, fontSize = 14.sp,
                            )
                        }
                    }
                    items(dirs, key = { it.absolutePath }) { d ->
                        Text(
                            d.name,
                            Modifier.fillMaxWidth().clickable { current = d }.padding(vertical = 11.dp),
                            fontSize = 14.sp,
                        )
                    }
                    if (dirs.isEmpty()) {
                        item { Text("（无子目录）", color = Theme.Muted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 11.dp)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "选中当前目录",
                        Modifier.clickable { onPicked(current.absolutePath) },
                        color = Theme.Accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Text("取消", Modifier.clickable(onClick = onDismiss), color = Theme.Muted, fontSize = 14.sp)
                }
            }
        }
    }
}

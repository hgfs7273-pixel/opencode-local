package com.opencodelocal.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.opencodelocal.app.backend.OpencodeBackend
import com.opencodelocal.app.backend.OpencodeInstaller
import com.opencodelocal.app.backend.Prefs
import com.opencodelocal.app.ui.ChatViewModel
import com.opencodelocal.app.ui.FilesViewModel
import com.opencodelocal.app.ui.HomeViewModel
import com.opencodelocal.app.ui.UpdateViewModel
import com.opencodelocal.app.ui.fmtTime
import com.opencodelocal.app.ui.theme.OpencodeLocalTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全圖形介面：設定 → 主頁 → 對話（SSE 串流＋真實批准卡）→ 檔案變更。
 * 沒有終端機，只有卡片、按鈕、氣泡、進度條。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpencodeLocalTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "setup") {
                    composable("setup") { SetupScreen { nav.navigate("home") } }
                    composable("home") {
                        HomeScreen(
                            onOpen = { nav.navigate("chat/$it") },
                            onLogout = { nav.navigate("setup") { popUpTo("home") { inclusive = true } } }
                        )
                    }
                    composable(
                        "chat/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { back ->
                        val id = back.arguments?.getString("id").orEmpty()
                        ChatScreen(
                            sessionId = id,
                            onBack = { nav.popBackStack() },
                            onFiles = { nav.navigate("files/$id") }
                        )
                    }
                    composable(
                        "files/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.StringType })
                    ) { back ->
                        val id = back.arguments?.getString("id").orEmpty()
                        FilesScreen(sessionId = id, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}

fun <T : ViewModel> vmKey(key: String, create: () -> T): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <M : ViewModel> create(modelClass: Class<M>): M = create() as M
    }

// ---------------- 設定頁 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val backend = remember { OpencodeBackend(ctx) }
    val prefs = remember { Prefs(ctx) }
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf(prefs.provider() ?: "openai") }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("尚未安裝") }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var autoTried by remember { mutableStateOf(false) }

    fun startWith(p: String, k: String) {
        busy = true
        scope.launch {
            if (!backend.isInstalled()) {
                status = "下載本地引擎中…"
                OpencodeInstaller(ctx, backend).install {
                    when (it) {
                        is OpencodeInstaller.Progress.Downloading -> { progress = it.percent; status = "下載中 ${it.percent}%" }
                        is OpencodeInstaller.Progress.Extracting -> status = "解壓安裝中…"
                        is OpencodeInstaller.Progress.Done -> status = "安裝完成，啟動中…"
                        is OpencodeInstaller.Progress.Failed -> { status = "失敗：${it.reason}"; busy = false; return@install }
                    }
                }
            }
            status = "啟動本地後端…"
            val ok = backend.startServer(k, p)
            busy = false
            if (ok) {
                prefs.saveAuth(p, k)
                onDone()
            } else status = "啟動失敗，請重試（需 arm64 手機）"
        }
    }

    // 記住 Key：每天打開自動啟動，不用重貼
    LaunchedEffect(Unit) {
        if (!autoTried) {
            autoTried = true
            val sp = prefs.provider()
            val sk = prefs.apiKey()
            if (!sp.isNullOrBlank() && !sk.isNullOrBlank()) {
                status = "已記住 Key，自動啟動中…"
                startWith(sp, sk)
            }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Opencode 本地版 · 設定") }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("1. 選擇模型商", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("openai" to "OpenAI", "anthropic" to "Anthropic", "gemini" to "Gemini").forEach { (id, name) ->
                    FilterChip(
                        selected = provider == id,
                        onClick = { provider = id },
                        label = { Text(name) },
                        leadingIcon = { if (provider == id) Icon(Icons.Default.Check, null) }
                    )
                }
            }
            OutlinedTextField(
                value = apiKey, onValueChange = { apiKey = it },
                label = { Text("API Key（只存本機）") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Text("2. 一鍵安裝並啟動（約 60MB，首次需網路）", style = MaterialTheme.typography.titleMedium)
            if (busy && progress in 1..99) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    if (apiKey.isBlank()) { status = "請先貼上 API Key"; return@Button }
                    startWith(provider, apiKey)
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text("一鍵安裝並啟動")
            }
            Text("程式碼跑在手機沙盒，模型 Key 不經第三方。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------- 主頁 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onLogout: () -> Unit) {
    val vm: HomeViewModel = viewModel()
    val up: UpdateViewModel = viewModel()
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showEngine by remember { mutableStateOf(false) }
    var showApp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("我的任務") },
                actions = {
                    IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "重新整理") }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("更新引擎 / 查看版本") },
                            onClick = { menu = false; showEngine = true; up.loadVersions() }
                        )
                        DropdownMenuItem(
                            text = { Text("檢查 App 更新") },
                            onClick = { menu = false; showApp = true; up.checkApp() }
                        )
                        DropdownMenuItem(
                            text = { Text("登出（清除 Key）") },
                            onClick = { menu = false; Prefs(ctx).clear(); onLogout() }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val title = "手機任務 " + SimpleDateFormat("M/d HH:mm", Locale.getDefault()).format(Date())
                    vm.create(title, onOpen)
                },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("新任務") }
            )
        }
    ) { pad ->
        if (vm.loading && vm.sessions.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(vm.sessions, key = { it.id }) { s ->
                    ElevatedCard(onClick = { onOpen(s.id) }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            headlineContent = { Text(s.title) },
                            supportingContent = { Text(fmtTime(s.updated) + " · 點我繼續") },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            trailingContent = {
                                Row {
                                    Icon(Icons.Default.ChevronRight, null)
                                    IconButton(onClick = { vm.remove(s.id) }) { Icon(Icons.Default.Delete, "刪除") }
                                }
                            }
                        )
                    }
                }
                if (vm.sessions.isEmpty()) item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PhoneAndroid, null, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("還沒有任務，按右下角「新任務」開始")
                        }
                    }
                }
            }
        }
    }

    // ---- 引擎更新對話框 ----
    if (showEngine) {
        AlertDialog(
            onDismissRequest = { if (!up.updating) showEngine = false },
            title = { Text("引擎更新") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已安裝：" + (up.engineVersion ?: "讀取中…"))
                    Text("上游最新：" + (up.engineLatest ?: "讀取中…"))
                    Text("更新只換引擎本體，你的程式碼和任務不會動。", style = MaterialTheme.typography.bodySmall)
                    if (up.updating && up.updateProgress in 1..99) {
                        LinearProgressIndicator(progress = { up.updateProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    if (up.updateStatus.isNotBlank()) Text(up.updateStatus, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { up.updateEngine() }, enabled = !up.updating) { Text("更新引擎") }
            },
            dismissButton = {
                TextButton(onClick = { showEngine = false }, enabled = !up.updating) { Text("關閉") }
            }
        )
    }

    // ---- App 更新對話框 ----
    if (showApp) {
        val r = up.appResult
        AlertDialog(
            onDismissRequest = { showApp = false },
            title = { Text("App 更新") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (r == null && up.checkingApp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp)); Text("檢查中…")
                        }
                    } else if (r == null) {
                        Text("檢查失敗，請稍後再試。")
                    } else {
                        Text("目前版本：" + r.current)
                        Text("最新版本：" + (r.latest ?: "未知"))
                        if (r.available) Text("有新版！按前往下載，覆蓋安裝即可，資料會保留。")
                        else Text("已經是最新版。")
                    }
                }
            },
            confirmButton = {
                if (r?.available == true && r.url != null) {
                    TextButton(onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.url)))
                    }) { Text("前往下載") }
                }
            },
            dismissButton = { TextButton(onClick = { showApp = false }) { Text("關閉") } }
        )
    }
}

// ---------------- 對話頁 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(sessionId: String, onBack: () -> Unit, onFiles: () -> Unit) {
    val vm: ChatViewModel = viewModel(key = sessionId, factory = vmKey(sessionId) { ChatViewModel(sessionId) })
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var showModels by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("對話 · ${vm.status}") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = {
                    if (vm.models.isNotEmpty()) {
                        TextButton(onClick = { showModels = true }) {
                            Text(vm.selectedModel?.let { it.providerName + "/" + it.modelName } ?: "預設模型")
                        }
                    }
                    TextButton(onClick = onFiles) { Text("變更(${vm.diffCount})") }
                }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (vm.error != null) {
                Card(Modifier.padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(vm.error!!, Modifier.padding(12.dp))
                }
            }
            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.messages.forEach { m ->
                    // 使用者訊息：靠右氣泡
                    m.texts.filter { it.isUser }.forEach { t ->
                        item(key = "u-" + t.id.ifBlank { t.text.hashCode().toString() }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = MaterialTheme.shapes.medium
                                ) { Text(t.text, Modifier.padding(12.dp)) }
                            }
                        }
                    }
                    // AI 文字：靠左氣泡
                    m.texts.filter { !it.isUser }.forEach { t ->
                        item(key = "a-" + t.id) {
                            ElevatedCard(Modifier.fillMaxWidth()) { Text(t.text, Modifier.padding(12.dp)) }
                        }
                    }
                    // 工具列：小狀態列
                    m.tools.forEach { tl ->
                        item(key = "t-" + tl.id) {
                            ListItem(
                                headlineContent = { Text("🔧 " + tl.tool, style = MaterialTheme.typography.bodyMedium) },
                                supportingContent = { Text(toolStatusText(tl.status), style = MaterialTheme.typography.bodySmall) },
                                leadingContent = { Icon(Icons.Default.Build, null) }
                            )
                        }
                    }
                    if (m.error != null && !m.texts.any { !it.isUser }) {
                        item(key = "e-" + m.id) {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                Text(m.error!!, Modifier.padding(12.dp))
                            }
                        }
                    }
                }
                // 串流中的文字
                vm.streaming.forEach { (mid, text) ->
                    item(key = "s-$mid") {
                        ElevatedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                if (text.isBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                        Spacer(Modifier.width(8.dp)); Text("思考中…")
                                    }
                                } else Text(text + "▍")
                            }
                        }
                    }
                }
                // 串流中的工具
                vm.liveTools.values.forEach { tl ->
                    item(key = "l-" + tl.id) {
                        ListItem(
                            headlineContent = { Text("🔧 " + tl.tool, style = MaterialTheme.typography.bodyMedium) },
                            supportingContent = { Text(toolStatusText(tl.status), style = MaterialTheme.typography.bodySmall) },
                            leadingContent = { Icon(Icons.Default.Build, null) }
                        )
                    }
                }
                // 真實批准卡（每張對應一次後端事件）
                vm.permissions.forEach { p ->
                    item(key = "p-" + p.id) {
                        OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(p.title, style = MaterialTheme.typography.titleSmall)
                                if (p.pattern.isNotBlank()) Text(p.type + "：\n" + p.pattern, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { vm.respond(p, "once") }) { Text("批准一次") }
                                    OutlinedButton(onClick = { vm.respond(p, "always") }) { Text("總是批准") }
                                    TextButton(onClick = { vm.respond(p, "reject") }) { Text("拒絕") }
                                }
                            }
                        }
                    }
                }
            }
            // 快捷動作（不用打指令）
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("解釋這個專案", "幫我修 bug", "加一個新功能").forEach {
                    AssistChip(
                        onClick = { scope.launch { vm.send(it) } },
                        label = { Text(it) },
                        enabled = !vm.busy
                    )
                }
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("輸入要 AI 做的事…") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                if (vm.busy) {
                    FilledTonalButton(onClick = { vm.stop() }) { Text("停止") }
                } else {
                    FilledIconButton(onClick = {
                        if (input.isNotBlank()) { vm.send(input); input = "" }
                    }) { Icon(Icons.Default.Send, "送出") }
                }
            }
        }
    }

    if (showModels) {
        AlertDialog(
            onDismissRequest = { showModels = false },
            title = { Text("選擇模型") },
            text = {
                LazyColumn {
                    item {
                        TextButton(onClick = { vm.selectedModel = null; showModels = false }) { Text("使用伺服器預設模型") }
                    }
                    items(vm.models, key = { it.providerId + "/" + it.modelId }) { m ->
                        TextButton(onClick = { vm.selectedModel = m; showModels = false }) {
                            Text(m.providerName + " / " + m.modelName)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModels = false }) { Text("關閉") } }
        )
    }
}

fun toolStatusText(status: String): String = when (status) {
    "pending" -> "等待中"
    "running" -> "執行中…"
    "completed" -> "完成"
    "error" -> "失敗"
    else -> status.ifBlank { "處理中…" }
}

// ---------------- 檔案變更頁 ----------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(sessionId: String, onBack: () -> Unit) {
    val vm: FilesViewModel = viewModel(key = "f-$sessionId", factory = vmKey(sessionId) { FilesViewModel(sessionId) })

    LaunchedEffect(Unit) { vm.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("檔案變更(${vm.diffs.size})") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } },
                actions = { IconButton(onClick = { vm.load() }) { Icon(Icons.Default.Refresh, "重新整理") } }
            )
        }
    ) { pad ->
        if (vm.loading && vm.diffs.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else if (vm.diffs.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { Text("這個任務還沒有檔案變更") }
        } else {
            LazyColumn(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(vm.diffs) { idx, d ->
                    ElevatedCard(onClick = { vm.expanded = if (vm.expanded == idx) null else idx }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Description, null)
                                Spacer(Modifier.width(8.dp))
                                Text(d.file, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AssistChip(onClick = {}, label = { Text("+" + d.additions) })
                                AssistChip(onClick = {}, label = { Text("-" + d.deletions) })
                            }
                            if (vm.expanded == idx) {
                                Row {
                                    FilterChip(selected = !vm.showBefore, onClick = { vm.showBefore = false }, label = { Text("修改後") })
                                    Spacer(Modifier.width(8.dp))
                                    FilterChip(selected = vm.showBefore, onClick = { vm.showBefore = true }, label = { Text("修改前") })
                                }
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                                    Text(
                                        if (vm.showBefore) d.before.ifBlank { "(空檔)" } else d.after.ifBlank { "(已刪除)" },
                                        Modifier.padding(10.dp),
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

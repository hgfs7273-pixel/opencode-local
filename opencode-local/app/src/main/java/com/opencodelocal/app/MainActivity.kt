package com.opencodelocal.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.opencodelocal.app.backend.OpencodeApi
import com.opencodelocal.app.backend.OpencodeBackend
import com.opencodelocal.app.backend.OpencodeInstaller
import com.opencodelocal.app.ui.theme.OpencodeLocalTheme
import kotlinx.coroutines.launch

/**
 * 全圖形介面：沒有終端機輸入框，只有卡片、按鈕、進度條、氣泡。
 * 流程：設定頁（選模型→貼Key→一鍵安裝/啟動）→ 主頁（任務卡片）→ 對話頁（氣泡+批准按鈕）
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpencodeLocalTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "setup") {
                    composable("setup") { SetupScreen { nav.navigate("home") } }
                    composable("home") { HomeScreen { nav.navigate("chat/$it") } }
                    composable("chat/{id}") { ChatScreen() }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val backend = remember { OpencodeBackend(ctx) }
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf("openai") }
    var apiKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("尚未安裝") }
    var progress by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

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
                        val ok = backend.startServer(apiKey, provider)
                        busy = false
                        if (ok) onDone() else status = "啟動失敗，請重試（需 arm64 手機）"
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp))
                Text("一鍵安裝並啟動")
            }
            Text("程式碼跑在手機沙盒 filesDir/workspace，模型 Key 不經第三方。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val api = remember { OpencodeApi() }
    val scope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf(listOf<OpencodeApi.Session>()) }

    LaunchedEffect(Unit) { scope.launch { sessions = api.listSessions() } }

    Scaffold(
        topBar = { TopAppBar(title = { Text("我的任務") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { api.sendMessage("new", "新任務"); sessions = api.listSessions() } },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("新任務") }
            )
        }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions, key = { it.id }) {
                ElevatedCard(onClick = { onOpen(it.id) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(it.title) },
                        supportingContent = { Text("點我繼續 · 本機執行中", style = MaterialTheme.typography.bodySmall) },
                        leadingContent = { Icon(Icons.Default.Folder, null) },
                        trailingContent = { Icon(Icons.Default.ChevronRight, null) }
                    )
                }
            }
            if (sessions.isEmpty()) item {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    val api = remember { OpencodeApi() }
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var messages by remember { mutableStateOf(listOf("歡迎！我是跑在你手機裡的 Opencode。")) }

    Scaffold(topBar = { TopAppBar(title = { Text("對話") }, navigationIcon = { Icon(Icons.Default.Chat, null) }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyColumn(Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(messages) {
                    ElevatedCard(Modifier.fillMaxWidth()) { Text(it, Modifier.padding(12.dp)) }
                }
                // 工具批准範例卡（真的批准會打 localhost API）
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("AI 想修改檔案：app/src/Main.kt", style = MaterialTheme.typography.titleSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { scope.launch { api.approveToolCall("s", "t", true) } }) { Text("批准") }
                                OutlinedButton(onClick = { scope.launch { api.approveToolCall("s", "t", false) } }) { Text("拒絕") }
                            }
                        }
                    }
                }
            }
            // 快捷動作（不用打指令）
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("解釋專案", "幫我修 bug", "加新功能").forEach {
                    AssistChip(onClick = { messages = messages + "你：$it"; scope.launch { api.sendMessage("s", it) } }, label = { Text(it) })
                }
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = { Text("用說的或用打的…") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { /* TODO: 語音輸入 */ }) { Icon(Icons.Default.Mic, "語音") }
                FilledIconButton(onClick = {
                    if (input.isNotBlank()) { messages = messages + "你：$input"; scope.launch { api.sendMessage("s", input) }; input = "" }
                }) { Icon(Icons.Default.Send, "送出") }
            }
        }
    }
}

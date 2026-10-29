package com.forceclient.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.Socket
import java.net.InetSocketAddress
import org.json.JSONObject
import java.util.Base64
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException

private val Brown = Color(0xFFB9825D)
private val Cream = Color(0xFFFFE0C2)
private val DarkBg = Color(0xFF17110F)
private val DarkCard = Color(0xFF261B17)
private val LightBg = Color(0xFFFFF8F3)
private val LightCard = Color(0xFFF2E4D9)
private val Ink = Color(0xFF281A14)
private const val MAX_CONFIG_BYTES = 1_048_576

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForceCApp(this) }
    }
}

private enum class Page { Home, Servers, Settings }
private data class Server(val name: String, val region: String, val ping: Int?, val host: String = "", val port: Int = 0)
private data class ImportedConfig(val fileName: String, val format: String, val profiles: Int)

@Composable
private fun ForceCApp(context: Context) {
    val prefs = remember { context.getSharedPreferences("forcec_preferences", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark_theme", true)) }
    var page by remember { mutableStateOf(Page.Home) }
    var sorted by remember { mutableStateOf(false) }
    var pingChecked by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importedConfig by remember { mutableStateOf(loadImportedConfig(context)) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var subscriptionUrl by remember { mutableStateOf("") }
    var urlLoading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var pinging by remember { mutableStateOf(false) }
    var servers by remember { mutableStateOf(loadServerProfiles(context)) }

    fun refreshSubscription() {
        val savedUrl = prefs.getString("subscription_url", null)
        if (savedUrl.isNullOrBlank()) {
            importMessage = "Сначала импортируйте подписку по HTTPS-ссылке — её адрес будет сохранён для обновления."
            return
        }
        if (refreshing) return
        refreshing = true
        importMessage = "Обновляю подписку…"
        Thread {
            val result = importConfigFromUrl(context, savedUrl)
            Handler(Looper.getMainLooper()).post {
                refreshing = false
                importMessage = if (result.first != null) "Подписка обновлена: " + result.first.profiles + " профилей." else result.second
                if (result.first != null) { importedConfig = result.first; servers = loadServerProfiles(context) }
                refreshCount++
            }
        }.start()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = importConfig(context, uri)
            importMessage = result.second
            if (result.first != null) importedConfig = result.first
        }
    }

    val bg = if (dark) DarkBg else LightBg
    val card = if (dark) DarkCard else LightCard
    val text = if (dark) Cream else Ink
    val muted = if (dark) Color(0xFFB9A49A) else Color(0xFF766158)
    val visibleServers = if (sorted) servers.sortedBy { it.ping ?: Int.MAX_VALUE } else servers
    fun checkPings() {
        if (pinging) return
        if (servers.isEmpty()) {
            importMessage = "Импортируйте конфигурацию, чтобы проверить её серверы."
            return
        }
        pinging = true
        Thread {
            val checked = servers.map { server ->
                val started = System.nanoTime()
                val ping = runCatching {
                    Socket().use { it.connect(InetSocketAddress(server.host, server.port), 2500) }
                    ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
                }.getOrNull()
                server.copy(ping = ping)
            }
            Handler(Looper.getMainLooper()).post {
                servers = checked
                pingChecked = true
                pinging = false
                importMessage = "Показан TCP-пинг узлов, не скорость VPN-туннеля."
            }
        }.start()
    }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Cream, secondary = Brown, background = bg, surface = card, onBackground = text, onSurface = text)
        else lightColorScheme(primary = Color(0xFF78492F), secondary = Brown, background = bg, surface = card, onBackground = text, onSurface = text)) {
        if (showUrlDialog) {
            AlertDialog(
                onDismissRequest = { if (!urlLoading) showUrlDialog = false },
                title = { Text("Импорт по ссылке") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Вставьте HTTPS-ссылку на подписку. ForceC попробует скачать список конфигураций.")
                        OutlinedTextField(
                            value = subscriptionUrl,
                            onValueChange = { subscriptionUrl = it },
                            label = { Text("HTTPS-ссылка") },
                            placeholder = { Text("https://example.com/subscription") },
                            singleLine = true,
                            enabled = !urlLoading,
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (urlLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                },
                confirmButton = {
                    TextButton(enabled = !urlLoading && subscriptionUrl.isNotBlank(), onClick = {
                        urlLoading = true
                        importMessage = "Загружаю подписку…"
                        val url = subscriptionUrl.trim()
                        Thread {
                            val result = importConfigFromUrl(context, url)
                            Handler(Looper.getMainLooper()).post {
                                urlLoading = false
                                importMessage = result.second
                                if (result.first != null) {
                                    importedConfig = result.first
                                    servers = loadServerProfiles(context)
                                    prefs.edit().putString("subscription_url", url).apply()
                                    showUrlDialog = false
                                }
                            }
                        }.start()
                    }) { Text("Импортировать") }
                },
                dismissButton = {
                    TextButton(enabled = !urlLoading, onClick = { showUrlDialog = false }) { Text("Отмена") }
                }
            )
        }
        Scaffold(containerColor = bg, bottomBar = {
            NavigationBar(containerColor = card) {
                NavigationBarItem(page == Page.Home, { page = Page.Home }, icon = { Icon(Icons.Default.Shield, null) }, label = { Text("Главная") })
                NavigationBarItem(page == Page.Servers, { page = Page.Servers }, icon = { Icon(Icons.Default.Public, null) }, label = { Text("Серверы") })
                NavigationBarItem(page == Page.Settings, { page = Page.Settings }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
            }
        }) { padding ->
            when (page) {
                Page.Home -> HomePage(Modifier.padding(padding), card, text, muted, importedConfig, importMessage,
                    onImport = { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                    onServers = { page = Page.Servers }, onImportUrl = { showUrlDialog = true }, onRefresh = { refreshSubscription() }, refreshCount = refreshCount)
                Page.Servers -> ServersPage(Modifier.padding(padding), card, text, muted, visibleServers, importedConfig, importMessage,
                    pingChecked, sorted, pinging, onPing = { checkPings() }, onSort = { sorted = !sorted },
                    onImport = { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                    onImportUrl = { showUrlDialog = true }, onRefresh = { refreshSubscription() }, refreshCount = refreshCount)
                Page.Settings -> SettingsPage(Modifier.padding(padding), card, text, muted, dark, onTheme = {
                    dark = it
                    prefs.edit().putBoolean("dark_theme", it).apply()
                })
            }
        }
    }
}

private fun loadImportedConfig(context: Context): ImportedConfig? {
    val file = File(context.filesDir, "configs/active_config.txt")
    if (!file.exists()) return null
    val name = File(context.filesDir, "configs/active_name.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty().ifBlank { "config.txt" }
    val content = runCatching { file.readText() }.getOrNull() ?: return null
    return ImportedConfig(name, detectFormat(name, content), countProfiles(content))
}

private fun loadServerProfiles(context: Context): List<Server> {
    val file = File(context.filesDir, "configs/active_config.txt")
    return if (file.exists()) parseServerProfiles(file.readText()) else emptyList()
}

private fun importConfig(context: Context, uri: Uri): Pair<ImportedConfig?, String> {
    return try {
        val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')?.take(100)?.ifBlank { "imported_config.txt" } ?: "imported_config.txt"
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_CONFIG_BYTES) throw IOException("Файл больше 1 МБ")
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        } ?: throw IOException("Не удалось открыть файл")
        val raw = bytes.toString(Charsets.UTF_8).trim()
        if (raw.isBlank()) throw IOException("Файл пустой")
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val recognized = raw.startsWith("{") || raw.startsWith("[") ||
            lines.any { line ->
                line.startsWith("vless://", true) || line.startsWith("vmess://", true) ||
                    line.startsWith("trojan://", true) || line.startsWith("ss://", true) ||
                    line.startsWith("https://", true) || line.startsWith("http://", true)
            }
        if (!recognized) throw IOException("Формат не распознан. Выберите JSON или текстовый файл с поддерживаемой конфигурацией.")
        val folder = File(context.filesDir, "configs").apply { mkdirs() }
        File(folder, "active_config.txt").writeText(raw)
        File(folder, "active_name.txt").writeText(name)
        val item = ImportedConfig(name, detectFormat(name, raw), countProfiles(raw))
        item to "Конфигурация импортирована локально. VPN-движок ещё не подключён."
    } catch (e: Exception) {
        null to (e.message ?: "Не удалось импортировать конфигурацию")
    }
}


private fun importConfigFromUrl(context: Context, value: String): Pair<ImportedConfig?, String> {
    var connection: HttpURLConnection? = null
    return try {
        val uri = Uri.parse(value)
        if (!value.startsWith("https://", true) || uri.host.isNullOrBlank()) {
            throw IOException("Для безопасности поддерживаются только корректные HTTPS-ссылки.")
        }
        connection = (URL(value).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "ForceCLIENT/0.3")
            setRequestProperty("Accept", "text/plain, application/json, */*")
        }
        val code = connection.responseCode
        if (code !in 200..299) throw IOException("Сервер вернул HTTP $code")
        val bytes = connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_CONFIG_BYTES) throw IOException("Подписка больше 1 МБ")
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
        var raw = bytes.toString(Charsets.UTF_8).trim()
        if (raw.isBlank()) throw IOException("Ссылка вернула пустой ответ.")
        if (!looksLikeConfig(raw)) {
            val decoded = runCatching {
                Base64.getMimeDecoder().decode(raw.replace("-", "+").replace("_", "/")).toString(Charsets.UTF_8).trim()
            }.getOrNull()
            if (decoded != null && looksLikeConfig(decoded)) raw = decoded
        }
        if (!looksLikeConfig(raw)) throw IOException("По ссылке не найдена подписка с поддерживаемыми конфигурациями (VLESS, VMess, Trojan, Shadowsocks или JSON).")
        val name = uri.host?.take(80)?.let { "$it-subscription.txt" } ?: "subscription.txt"
        val folder = File(context.filesDir, "configs").apply { mkdirs() }
        File(folder, "active_config.txt").writeText(raw)
        File(folder, "active_name.txt").writeText(name)
        val item = ImportedConfig(name, detectFormat(name, raw), countProfiles(raw))
        item to "Подписка загружена. Профилей найдено: ${item.profiles}. Для подключения ещё нужен VPN-движок."
    } catch (e: Exception) {
        null to (e.message ?: "Не удалось загрузить подписку.")
    } finally {
        connection?.disconnect()
    }
}

private fun parseServerProfiles(raw: String): List<Server> {
    val found = mutableListOf<Server>()
    raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEachIndexed { index, line ->
        runCatching {
            var host: String? = null
            var port: Int? = null
            var name = "Узел " + (index + 1)
            if (line.startsWith("vmess://", true)) {
                val payload = line.substringAfter("vmess://").substringBefore("#")
                val decoded = Base64.getMimeDecoder().decode(payload.replace("-", "+").replace("_", "/")).toString(Charsets.UTF_8)
                val json = JSONObject(decoded)
                host = json.optString("add").takeIf { it.isNotBlank() }
                port = json.optString("port").toIntOrNull()
                name = json.optString("ps", name).ifBlank { name }
            } else if (line.startsWith("vless://", true) || line.startsWith("trojan://", true) || line.startsWith("ss://", true)) {
                val uri = Uri.parse(line)
                host = uri.host
                port = uri.port.takeIf { it > 0 }
                name = uri.fragment?.let { Uri.decode(it) }?.ifBlank { null } ?: name
                if (host.isNullOrBlank() && line.startsWith("ss://", true)) {
                    val auth = line.substringAfter("ss://").substringBefore("#").substringBefore("?").substringAfterLast("@", "")
                    if (auth.contains(":")) { host = auth.substringBeforeLast(":").removeSurrounding("[", "]"); port = auth.substringAfterLast(":").toIntOrNull() }
                }
            }
            if (!host.isNullOrBlank() && port != null && port in 1..65535) found.add(Server(name, host, null, host, port))
        }
    }
    return found.distinctBy { it.host + ":" + it.port }
}

private fun looksLikeConfig(raw: String): Boolean {
    val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
    return raw.startsWith("{") || raw.startsWith("[") || lines.any { line ->
        line.startsWith("vless://", true) || line.startsWith("vmess://", true) ||
            line.startsWith("trojan://", true) || line.startsWith("ss://", true)
    }
}

private fun detectFormat(name: String, raw: String): String = when {
    raw.startsWith("vless://", true) -> "VLESS-ссылка"
    raw.startsWith("vmess://", true) -> "VMess-ссылка"
    raw.startsWith("trojan://", true) -> "Trojan-ссылка"
    raw.startsWith("ss://", true) -> "Shadowsocks-ссылка"
    raw.startsWith("{") || raw.startsWith("[") || name.endsWith(".json", true) -> "JSON"
    else -> "Текстовая конфигурация"
}

private fun countProfiles(raw: String): Int {
    val lines = raw.lines().map { it.trim() }.filter {
        it.startsWith("vless://", true) || it.startsWith("vmess://", true) ||
            it.startsWith("trojan://", true) || it.startsWith("ss://", true)
    }
    return if (lines.isNotEmpty()) lines.size else 1
}

@Composable
private fun HomePage(modifier: Modifier, card: Color, text: Color, muted: Color, config: ImportedConfig?,
                     importMessage: String?, onImport: () -> Unit, onServers: () -> Unit, onImportUrl: () -> Unit, onRefresh: () -> Unit, refreshCount: Int) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = Brown, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("F", color = Cream, fontSize = 30.sp, fontWeight = FontWeight.Black) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ForceCLIENT", color = text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Сильное соединение. Просто.", color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Surface(color = card, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(76.dp).background(Brown.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Shield, null, tint = text, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text("VPN не подключён", color = text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text("Импортируйте конфигурацию для подготовки", color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Импортировать конфиг", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onImportUrl, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("Импортировать по ссылке")
                    }
                }
            }
        }
        item {
            Text("Моя конфигурация", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Description, null, tint = Brown, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config?.fileName ?: "Пока нет конфигурации", color = text, fontWeight = FontWeight.SemiBold)
                        Text(config?.let { "${it.format} · профилей: ${it.profiles}" } ?: "Импортируйте .json или текстовую ссылку", color = muted, fontSize = 12.sp)
                    }
                    if (config != null) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF63B77B))
                }
            }
            if (importMessage != null) Text(importMessage, color = muted, fontSize = 12.sp)
        }
        item {
            Surface(onClick = onServers, color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, null, tint = Brown, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Серверы", color = text, fontWeight = FontWeight.SemiBold)
                        Text("Проверка задержки и сортировка", color = muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = muted)
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Обновить конфигурации")
            }
            Text(when { refreshing -> "Загружаю свежие конфигурации…"; refreshCount > 0 -> "Последняя попытка обновления выполнена."; else -> "При импорте по ссылке адрес подписки сохраняется автоматически." }, color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServersPage(modifier: Modifier, card: Color, text: Color, muted: Color, servers: List<Server>,
                        config: ImportedConfig?, importMessage: String?, pingChecked: Boolean, sorted: Boolean, pinging: Boolean,
                        onPing: () -> Unit, onSort: () -> Unit, onImport: () -> Unit, onImportUrl: () -> Unit, onRefresh: () -> Unit, refreshCount: Int) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Серверы", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Выберите подходящую локацию", color = muted)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPing, enabled = !pinging && servers.isNotEmpty(), modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Speed, null); Spacer(Modifier.width(6.dp)); Text(if (pinging) "Проверяю…" else "TCP-пинг")
            }
            OutlinedButton(onClick = onSort, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Sort, null); Text(if (sorted) "Сбросить" else "Сортировать")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("Импортировать конфиг")
        }
        OutlinedButton(onClick = onImportUrl, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Link, null); Spacer(Modifier.width(6.dp)); Text("Импортировать по ссылке")
        }
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Обновить конфигурации")
        }
        Text(when {
            config != null -> "Импортировано: ${config.fileName}. Узлы извлечены из конфигурации."
            else -> "Импортируйте конфиг или подписку, чтобы увидеть серверы."
        }, color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        if (importMessage != null) Text(importMessage, color = muted, fontSize = 12.sp)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(servers) { server ->
                Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, tint = Brown, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, color = text, fontWeight = FontWeight.SemiBold)
                            Text(server.host + ":" + server.port, color = muted, fontSize = 11.sp)
                        }
                        Text(server.ping?.let { "$it ms" } ?: "— ms", color = text, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (pinging) Text("Проверяю TCP-доступность узлов…", color = muted, fontSize = 12.sp)
        if (refreshing) Text("Обновляю подписку…", color = muted, fontSize = 12.sp)
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, card: Color, text: Color, muted: Color, dark: Boolean, onTheme: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Настройки", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Настройте ForceC под себя", color = muted)
        Spacer(Modifier.height(22.dp))
        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (dark) Icons.Default.DarkMode else Icons.Default.LightMode, null, tint = Brown, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Тёмная тема", color = text, fontWeight = FontWeight.SemiBold)
                    Text("Выбор сохраняется после перезапуска приложения", color = muted, fontSize = 12.sp)
                }
                Switch(checked = dark, onCheckedChange = onTheme)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("ForceCLIENT · 0.2.0", color = text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Material 3 · импорт конфигураций · VPN-движок ещё не подключён", color = muted, fontSize = 12.sp)
            }
        }
    }
}

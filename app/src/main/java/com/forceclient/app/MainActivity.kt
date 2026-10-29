package com.forceclient.app

import android.content.Context
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.Intent
import android.app.Activity
import android.net.VpnService
import com.forceclient.app.vpn.ForceVpnService
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.io.IOException

private val LocalForceAccent = compositionLocalOf { Color(0xFFB9825D) }
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
    var accentArgb by remember { mutableIntStateOf(prefs.getInt("accent_color", 0xFFB9825D.toInt())) }
    var showAccentDialog by remember { mutableStateOf(false) }
    val accentColor = Color(accentArgb)
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
    var vpnState by remember { mutableStateOf(ForceVpnService.STATE_DISCONNECTED) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == ForceVpnService.ACTION_STATE) {
                    vpnState = intent.getStringExtra(ForceVpnService.EXTRA_STATE) ?: ForceVpnService.STATE_DISCONNECTED
                    intent.getStringExtra(ForceVpnService.EXTRA_MESSAGE)?.let { importMessage = it }
                }
            }
        }
        val filter = IntentFilter(ForceVpnService.ACTION_STATE)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val start = Intent(context, ForceVpnService::class.java).setAction(ForceVpnService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(start) else context.startService(start)
            vpnState = "connecting"
            importMessage = "Запускаю Xray-core…"
        } else {
            importMessage = "Для подключения нужно разрешить Android VPN-соединение."
        }
    }

    fun toggleVpn() {
        if (vpnState == ForceVpnService.STATE_CONNECTED || vpnState == "connecting") {
            context.startService(Intent(context, ForceVpnService::class.java).setAction(ForceVpnService.ACTION_STOP))
            vpnState = ForceVpnService.STATE_DISCONNECTED
            return
        }
        if (importedConfig == null) {
            importMessage = "Сначала импортируйте рабочий профиль или подписку."
            return
        }
        val prepare = VpnService.prepare(context)
        if (prepare == null) {
            val start = Intent(context, ForceVpnService::class.java).setAction(ForceVpnService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(start) else context.startService(start)
            vpnState = "connecting"
        } else {
            vpnPermissionLauncher.launch(prepare)
        }
    }

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
                importMessage = if (result.first != null) "Подписка обновлена: " + (result.first?.profiles ?: 0) + " профилей." else result.second
                if (result.first != null) { importedConfig = result.first; servers = loadServerProfiles(context) }
                refreshCount++
            }
        }.start()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            val result = importConfig(context, uri)
            importMessage = result.second
            if (result.first != null) { importedConfig = result.first; servers = loadServerProfiles(context) }
        }
    }

    val bg = if (dark) DarkBg else LightBg
    val card = if (dark) DarkCard else LightCard
    val text = if (dark) Cream else Ink
    val muted = if (dark) Color(0xFFB9A49A) else Color(0xFF766158)
    val visibleServers = if (sorted) servers.sortedBy { it.ping ?: Int.MAX_VALUE } else servers
    fun checkPings() {
        if (pinging) return
        val currentServers = servers
        val configFile = File(context.filesDir, "configs/active_config.txt")
        if (!configFile.isFile) {
            importMessage = "Сначала импортируйте конфигурацию или подписку."
            return
        }
        pinging = true
        importMessage = "Проверяю задержку через Xray-core…"
        Thread {
            var checked: List<Server> = emptyList()
            var failure: String? = null
            try {
                val raw = configFile.readText().trim()
                val results = XrayEngine.pingProfiles(raw)
                val reachable = results.filter { it.success && it.delayMs != null }
                if (reachable.isEmpty()) {
                    failure = "Xray ping не получил ответа ни от одного узла. Проверьте серверы или попробуйте другую сеть."
                    checked = if (currentServers.isNotEmpty()) currentServers.map { it.copy(ping = null) } else {
                        results.map { Server(it.tag, it.host, null, it.host, it.port) }
                    }
                } else {
                    checked = if (currentServers.isEmpty()) {
                        results.map { Server(it.tag, it.host, it.delayMs, it.host, it.port) }
                    } else {
                        currentServers.map { server ->
                            val result = results.firstOrNull {
                                it.host.equals(server.host, ignoreCase = true) &&
                                    it.port == server.port && it.success
                            }
                            server.copy(ping = result?.delayMs)
                        }
                    }
                    val best = reachable.minByOrNull { it.delayMs!! }
                    failure = "Проверено Xray-core: доступно ${reachable.size} из ${results.size} узлов" +
                        (best?.let { ". Самый быстрый: ${it.tag} · ${it.delayMs} мс." } ?: ".")
                }
            } catch (e: Exception) {
                failure = e.message ?: "Не удалось проверить серверы через Xray-core."
                checked = currentServers.map { it.copy(ping = null) }
            }
            Handler(Looper.getMainLooper()).post {
                servers = checked
                pingChecked = checked.any { it.ping != null }
                pinging = false
                importMessage = failure
            }
        }.start()
    }

    CompositionLocalProvider(LocalForceAccent provides accentColor) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = accentColor, secondary = accentColor, background = bg, surface = card, onBackground = text, onSurface = text)
        else lightColorScheme(primary = accentColor, secondary = accentColor, background = bg, surface = card, onBackground = text, onSurface = text)) {
        if (showAccentDialog) AccentColorDialog(
            initialColor = accentColor,
            dark = dark,
            onDismiss = { showAccentDialog = false },
            onApply = { selected -> accentArgb = selected.toArgb(); prefs.edit().putInt("accent_color", accentArgb).apply(); showAccentDialog = false }
        )
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
                    onServers = { page = Page.Servers }, onImportUrl = { showUrlDialog = true }, onRefresh = { refreshSubscription() }, refreshCount = refreshCount, refreshing = refreshing, vpnState = vpnState, onConnect = { toggleVpn() })
                Page.Servers -> ServersPage(Modifier.padding(padding), card, text, muted, visibleServers, importedConfig, importMessage,
                    pingChecked, sorted, pinging, onPing = { checkPings() }, onSort = { sorted = !sorted },
                    onImport = { filePicker.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                    onImportUrl = { showUrlDialog = true }, onRefresh = { refreshSubscription() }, refreshCount = refreshCount)
                Page.Settings -> SettingsPage(Modifier.padding(padding), card, text, muted, dark, accentColor, onAccent = { showAccentDialog = true }, onPreset = { accentArgb = it; prefs.edit().putInt("accent_color", it).apply() }, onTheme = {
                    dark = it
                    prefs.edit().putBoolean("dark_theme", it).apply()
                })
            }
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
        item to "Конфигурация импортирована. Xray-core обработает её при подключении."
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
        item to "Подписка загружена. Профилей найдено: ${item.profiles}. Можно запускать VPN."
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
                     importMessage: String?, onImport: () -> Unit, onServers: () -> Unit, onImportUrl: () -> Unit, onRefresh: () -> Unit, refreshCount: Int, refreshing: Boolean, vpnState: String, onConnect: () -> Unit) {
    val pulse by rememberInfiniteTransition(label = "connectionPulse").animateFloat(
        initialValue = 0.94f, targetValue = 1.04f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulseScale"
    )
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(16.dp), color = LocalForceAccent.current, modifier = Modifier.size(48.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("F", color = Cream, fontSize = 30.sp, fontWeight = FontWeight.Black) }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ForceCLIENT", color = text, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Твой интернет. Под твоим контролем.", color = muted, fontSize = 12.sp)
                }
            }
        }
        item {
            Surface(color = card, shape = RoundedCornerShape(30.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
                Column(Modifier.padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size((112 * pulse).dp).background(LocalForceAccent.current.copy(alpha = .10f), CircleShape), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(88.dp).background(LocalForceAccent.current.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                            Surface(shape = CircleShape, color = LocalForceAccent.current, modifier = Modifier.size(68.dp)) {
                                Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.Shield, null, tint = Cream, modifier = Modifier.size(38.dp)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(color = LocalForceAccent.current.copy(alpha = .14f), shape = RoundedCornerShape(50)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(7.dp).background(Color(0xFFE0A05F), CircleShape))
                            Spacer(Modifier.width(7.dp))
                            Text(when (vpnState) { ForceVpnService.STATE_CONNECTED -> "ЗАЩИЩЕНО"; "connecting" -> "ПОДКЛЮЧЕНИЕ…"; ForceVpnService.STATE_ERROR -> "ОШИБКА"; else -> "НЕ ПОДКЛЮЧЕНО" }, color = text, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(when (vpnState) { ForceVpnService.STATE_CONNECTED -> "Соединение защищено"; "connecting" -> "Подключаемся к серверу…"; ForceVpnService.STATE_ERROR -> "Не удалось подключиться"; else -> "Готов к защищённому соединению?" }, color = text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text(when (vpnState) { ForceVpnService.STATE_CONNECTED -> "Трафик передаётся через Xray-core"; "connecting" -> "Настраиваем VPN-туннель Android"; else -> "Импортируй профиль, затем подключись" }, color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onConnect, enabled = vpnState != "connecting", modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(if (vpnState == ForceVpnService.STATE_CONNECTED) Icons.Default.LinkOff else Icons.Default.Shield, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (vpnState == ForceVpnService.STATE_CONNECTED) "Отключиться" else if (vpnState == "connecting") "Подключение…" else "Подключиться", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text("Добавить конфигурацию")
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onImportUrl, modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(Icons.Default.Link, null); Spacer(Modifier.width(8.dp)); Text("Импортировать по ссылке")
                    }
                    Text("Сетевой движок: Xray-core · Android VPNService", color = muted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                }
            }
        }
        item {
            Text("Твой профиль", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Surface(color = card, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().animateContentSize()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(color = LocalForceAccent.current.copy(alpha = .15f), shape = RoundedCornerShape(14.dp)) {
                        Icon(Icons.Default.Description, null, tint = LocalForceAccent.current, modifier = Modifier.padding(10.dp).size(26.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(config?.fileName ?: "Пока нет конфигурации", color = text, fontWeight = FontWeight.SemiBold)
                        Text(config?.let { "${it.format} · профилей: ${it.profiles}" } ?: "Импортируйте .json или текстовую ссылку", color = muted, fontSize = 12.sp)
                    }
                    if (config != null) Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF63B77B))
                }
            }
            AnimatedVisibility(visible = importMessage != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Surface(color = card, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, null, tint = LocalForceAccent.current, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(importMessage.orEmpty(), color = muted, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            Surface(onClick = onServers, color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, null, tint = LocalForceAccent.current, modifier = Modifier.size(28.dp))
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
        Text("Найди подходящий узел", color = muted, fontSize = 14.sp)
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
                        Icon(Icons.Default.Dns, null, tint = LocalForceAccent.current, modifier = Modifier.size(28.dp))
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
    }
}

@Composable
private fun SettingsPage(modifier: Modifier, card: Color, text: Color, muted: Color, dark: Boolean, accentColor: Color, onAccent: () -> Unit, onPreset: (Int) -> Unit, onTheme: (Boolean) -> Unit) {
    Column(modifier.fillMaxSize().padding(20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Настройки", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Сделай ForceC удобным для себя", color = muted, fontSize = 14.sp)
        Spacer(Modifier.height(22.dp))
        Text("ВНЕШНИЙ ВИД", color = muted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        Spacer(Modifier.height(9.dp))
        Surface(color = card, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Акцентный цвет", color = text, fontWeight = FontWeight.SemiBold)
                Text("Меняет основные кнопки и выделения", color = muted, fontSize = 12.sp)
                Spacer(Modifier.height(13.dp))
                val presets = listOf("Карамель" to 0xFFB9825D, "Синий" to 0xFF75A7FF, "Мята" to 0xFF72CBB0, "Лиловый" to 0xFFB8A0FF, "Розовый" to 0xFFFF91B5, "Янтарь" to 0xFFFFB74D, "Рубин" to 0xFFE87979, "Изумруд" to 0xFF62B98D)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    presets.forEach { (label, value) ->
                        val color = Color(value.toInt())
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Surface(onClick = { onPreset(value.toInt()) }, shape = CircleShape, color = color, modifier = Modifier.size(42.dp), border = if (accentColor == color) BorderStroke(3.dp, text) else null) {
                                Box(contentAlignment = Alignment.Center) { if (accentColor == color) Icon(Icons.Default.Check, null, tint = if (color.luminance() > .5f) Color.Black else Color.White) }
                            }
                            Spacer(Modifier.height(4.dp)); Text(label, color = muted, fontSize = 10.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onAccent, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(15.dp)) {
                    Icon(Icons.Default.Palette, null); Spacer(Modifier.width(8.dp)); Text("Свой цвет…")
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (dark) Icons.Default.DarkMode else Icons.Default.LightMode, null, tint = LocalForceAccent.current, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Тёмная тема", color = text, fontWeight = FontWeight.SemiBold)
                    Text(if (dark) "Шоколадный фон и тёплые акценты" else "Светлая кремовая палитра", color = muted, fontSize = 12.sp)
                }
                Switch(checked = dark, onCheckedChange = onTheme)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("ForceCLIENT · 0.2.0", color = text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Material 3 · Xray-core · Android VPNService", color = muted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun AccentColorDialog(initialColor: Color, dark: Boolean, onDismiss: () -> Unit, onApply: (Color) -> Unit) {
    val hsv = remember(initialColor) {
        val values = FloatArray(3)
        android.graphics.Color.colorToHSV(initialColor.toArgb(), values)
        values
    }
    var hue by remember(initialColor) { mutableFloatStateOf(hsv[0]) }
    var saturation by remember(initialColor) { mutableFloatStateOf(hsv[1].coerceAtLeast(.2f)) }
    var brightness by remember(initialColor) { mutableFloatStateOf(hsv[2].coerceAtLeast(.25f)) }
    val selected = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)))
    val panel = if (dark) Color(0xFF261B17) else Color(0xFFFFF8F3)
    val ink = if (dark) Color(0xFFFFE0C2) else Color(0xFF281A14)
    val muted = if (dark) Color(0xFFB9A49A) else Color(0xFF766158)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = panel,
        shape = RoundedCornerShape(30.dp),
        title = {
            Column {
                Text("Твой акцент", color = ink, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Настрой цвет интерфейса под себя", color = muted, fontSize = 13.sp)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(color = selected, shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth().height(92.dp)) {
                    Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Icon(Icons.Default.Palette, null, tint = if (selected.luminance() > .5f) Color.Black else Color.White)
                        Text("#" + selected.toArgb().toUInt().toString(16).takeLast(6).uppercase(), color = if (selected.luminance() > .5f) Color.Black else Color.White, fontWeight = FontWeight.Bold)
                    }
                }
                Text("Оттенок", color = ink, fontWeight = FontWeight.SemiBold)
                Slider(value = hue, onValueChange = { hue = it }, valueRange = 0f..360f)
                Text("Насыщенность", color = ink, fontWeight = FontWeight.SemiBold)
                Slider(value = saturation, onValueChange = { saturation = it }, valueRange = 0f..1f)
                Text("Яркость", color = ink, fontWeight = FontWeight.SemiBold)
                Slider(value = brightness, onValueChange = { brightness = it }, valueRange = .15f..1f)
            }
        },
        confirmButton = {
            Button(onClick = { onApply(selected) }, shape = RoundedCornerShape(14.dp)) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}


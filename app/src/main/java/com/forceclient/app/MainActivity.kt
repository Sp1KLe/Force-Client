package com.forceclient.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

private val Brown = Color(0xFFB9825D)
private val Cream = Color(0xFFFFE0C2)
private val DarkBg = Color(0xFF17110F)
private val DarkCard = Color(0xFF261B17)
private val LightBg = Color(0xFFFFF8F3)
private val LightCard = Color(0xFFF2E4D9)
private val Ink = Color(0xFF281A14)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ForceCApp() }
    }
}

private enum class Page { Home, Servers, Settings }
private data class Server(val name: String, val region: String, val ping: Int?)

@Composable
private fun ForceCApp() {
    var dark by remember { mutableStateOf(true) }
    var page by remember { mutableStateOf(Page.Home) }
    var demoConnected by remember { mutableStateOf(false) }
    var sorted by remember { mutableStateOf(false) }
    var pingChecked by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableIntStateOf(0) }
    val bg = if (dark) DarkBg else LightBg
    val card = if (dark) DarkCard else LightCard
    val text = if (dark) Cream else Ink
    val muted = if (dark) Color(0xFFB9A49A) else Color(0xFF766158)
    val servers = listOf(
        Server("Нидерланды · Амстердам", "ЕВРОПА", if (pingChecked) 24 else null),
        Server("Германия · Франкфурт", "ЕВРОПА", if (pingChecked) 38 else null),
        Server("США · Нью-Йорк", "АМЕРИКА", if (pingChecked) 112 else null),
        Server("Сингапур", "АЗИЯ", if (pingChecked) 186 else null)
    ).let { if (sorted && pingChecked) it.sortedBy { s -> s.ping ?: Int.MAX_VALUE } else it }

    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Cream, secondary = Brown, background = bg, surface = card, onBackground = text, onSurface = text)
        else lightColorScheme(primary = Color(0xFF78492F), secondary = Brown, background = bg, surface = card, onBackground = text, onSurface = text)) {
        Scaffold(containerColor = bg, bottomBar = {
            NavigationBar(containerColor = card) {
                NavigationBarItem(page == Page.Home, { page = Page.Home }, icon = { Icon(Icons.Default.Shield, null) }, label = { Text("Главная") })
                NavigationBarItem(page == Page.Servers, { page = Page.Servers }, icon = { Icon(Icons.Default.Public, null) }, label = { Text("Серверы") })
                NavigationBarItem(page == Page.Settings, { page = Page.Settings }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Настройки") })
            }
        }) { padding ->
            when (page) {
                Page.Home -> HomePage(Modifier.padding(padding), card, text, muted, demoConnected,
                    onToggle = { demoConnected = !demoConnected }, onServers = { page = Page.Servers },
                    onRefresh = { refreshCount++ }, refreshCount = refreshCount)
                Page.Servers -> ServersPage(Modifier.padding(padding), card, text, muted, servers,
                    pingChecked, sorted, onPing = { pingChecked = true }, onSort = { sorted = !sorted },
                    onRefresh = { refreshCount++ }, refreshCount = refreshCount)
                Page.Settings -> SettingsPage(Modifier.padding(padding), card, text, muted, dark, onTheme = { dark = it })
            }
        }
    }
}

@Composable
private fun HomePage(modifier: Modifier, card: Color, text: Color, muted: Color, connected: Boolean,
                     onToggle: () -> Unit, onServers: () -> Unit, onRefresh: () -> Unit, refreshCount: Int) {
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
                    Box(Modifier.size(76.dp).background(if (connected) Color(0xFF355C43) else Brown.copy(alpha = .18f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Shield, null, tint = if (connected) Color(0xFFB7F0C5) else text, modifier = Modifier.size(42.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(if (connected) "Демо включено" else "Защита не активна", color = text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Text(if (connected) "Это демонстрация — VPN-туннель не запущен" else "Выберите сервер, чтобы продолжить", color = muted, fontSize = 12.sp)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(18.dp)) {
                        Icon(if (connected) Icons.Default.PowerSettingsNew else Icons.Default.Bolt, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (connected) "Отключить демо" else "Подключить", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item {
            Text("Быстрый доступ", color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Surface(onClick = onServers, color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Public, null, tint = Brown, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Выбрать сервер", color = text, fontWeight = FontWeight.SemiBold)
                        Text("Пинг и список локаций", color = muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = muted)
                }
            }
        }
        item {
            OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Обновить конфигурации")
            }
            if (refreshCount > 0) Text("Нажатий: $refreshCount · источник конфигураций ещё не настроен", color = muted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ServersPage(modifier: Modifier, card: Color, text: Color, muted: Color, servers: List<Server>,
                        pingChecked: Boolean, sorted: Boolean, onPing: () -> Unit, onSort: () -> Unit,
                        onRefresh: () -> Unit, refreshCount: Int) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(24.dp))
        Text("Серверы", color = text, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text("Выберите подходящую локацию", color = muted)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPing, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Speed, null); Spacer(Modifier.width(6.dp)); Text("Проверить пинг")
            }
            OutlinedButton(onClick = onSort, shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Default.Sort, null); Text(if (sorted) "Сбросить" else "Сортировать")
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRefresh, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Обновить конфигурации")
        }
        Text(if (pingChecked) "Показаны примерные значения — реальные измерения появятся после подключения сетевого модуля." else "Это демонстрационные серверы. Нажмите проверку пинга для просмотра примера.", color = muted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(servers) { server ->
                Surface(color = card, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Dns, null, tint = Brown, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(server.name, color = text, fontWeight = FontWeight.SemiBold)
                            Text(server.region, color = muted, fontSize = 11.sp)
                        }
                        Text(server.ping?.let { "$it ms" } ?: "— ms", color = text, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        if (refreshCount > 0) Text("Обновление пока не настроено: добавьте источник конфигов.", color = muted, fontSize = 12.sp)
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
                    Text("Переключить тёмное и светлое оформление", color = muted, fontSize = 12.sp)
                }
                Switch(checked = dark, onCheckedChange = onTheme)
            }
        }
        Spacer(Modifier.height(12.dp))
        Surface(color = card, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp)) {
                Text("ForceCLIENT · 0.1.0", color = text, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Material 3 · ранний прототип интерфейса", color = muted, fontSize = 12.sp)
            }
        }
    }
}

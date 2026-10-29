package com.forceclient.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.forceclient.app.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicReference

/**
 * Android VPN lifecycle for the bundled libXray engine.
 *
 * A connection is only announced after libXray accepts the configuration.
 * The service intentionally refuses to start when the native engine or a valid
 * imported profile is missing; it never simulates a connected state.
 */
class ForceVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    @Volatile private var stopping = false
    @Volatile private var paused = false
    private var engineClass: Class<*>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_PAUSE -> pauseTunnel()
            ACTION_RESUME -> startTunnel()
            ACTION_START -> startTunnel()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (tunnel != null) return
        paused = false
        stopping = false
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Подготовка защищённого соединения…"))
        Thread({
            try {
                val profileFile = File(filesDir, "configs/active_config.txt")
                if (!profileFile.isFile || profileFile.length() == 0L) {
                    throw IllegalStateException("Сначала импортируйте профиль или подписку.")
                }
                val raw = profileFile.readText().trim()
                if (raw.isBlank()) throw IllegalStateException("Импортированный профиль пустой.")

                val core = findCoreClass()
                engineClass = core

                // Measure real latency through Xray and select the fastest reachable node.
                val prefs = getSharedPreferences("forcec_preferences", MODE_PRIVATE)
                val selectedHost = prefs.getString("selected_server_host", null)
                val selectedPort = prefs.getInt("selected_server_port", 0)
                val selected = if (!selectedHost.isNullOrBlank() && selectedPort > 0) {
                    com.forceclient.app.XrayEngine.selectProfileByEndpoint(raw, selectedHost, selectedPort)
                } else {
                    com.forceclient.app.XrayEngine.selectFastestProfile(raw)
                }
                val protectController = registerSocketProtection(core)
                    ?: throw IllegalStateException("Не удалось настроить защиту сокетов Xray. Запуск остановлен, чтобы избежать сетевой петли.")
                val setDns = core.methods.firstOrNull { it.name.equals("setDNS", ignoreCase = true) && it.parameterTypes.size == 2 }
                    ?: throw IllegalStateException("В libXray отсутствует безопасная настройка DNS.")
                setDns.invoke(null, protectController, "1.1.1.1:53")

                val config = normalizeConfig(buildConfig(JSONArray().put(selected.outbound)))
                val builder = Builder()
                    .setSession("ForceCLIENT")
                    .setMtu(1500)
                    .addAddress("10.111.0.1", 30)
                    .addRoute("0.0.0.0", 0)
                    .addAddress("fd00:fc::1", 126)
                    .addRoute("::", 0)
                    .addDnsServer("1.1.1.1")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    builder.setMetered(false)
                }
                val established = builder.establish() ?: throw IllegalStateException("Android не разрешил создать VPN-туннель.")
                tunnel = established

                config.put("env", config.optJSONObject("env") ?: JSONObject())
                config.getJSONObject("env").put("xray.tun.fd", established.fd.toString())
                val runPayload = JSONObject().put("xrayJson", config.toString())
                // libXray.runXray returns after Start succeeds; it is not a long-running call.
                invokeCore(core, "runXray", runPayload)

                var started = false
                val deadline = System.currentTimeMillis() + 15_000L
                while (!stopping && System.currentTimeMillis() < deadline) {
                    val state = invokeCore(core, "getXrayState", JSONObject())
                    if (state.optJSONObject("data")?.optBoolean("running", false) == true) {
                        started = true
                        break
                    }
                    Thread.sleep(250)
                }
                if (!stopping && !started) {
                    throw IllegalStateException("Xray-core не подтвердил запуск за 15 секунд.")
                }
                if (stopping) return@Thread

                broadcastState(STATE_CONNECTED, "Подключено · ${if (selected.delayMs >= 0) "${selected.delayMs} мс" else "сервер выбран вручную"} · ${selected.tag}")
                updateNotification("VPN включён · ${selected.tag}", connected = true)
                while (!stopping && !paused) {
                    val state = invokeCore(core, "getXrayState", JSONObject())
                    if (state.optJSONObject("data")?.optBoolean("running", false) != true) {
                        throw IllegalStateException("Xray-core остановился. Попробуйте другой сервер.")
                    }
                    Thread.sleep(1000)
                }
                if (paused && !stopping) return@Thread
            } catch (e: Exception) {
                broadcastState(STATE_ERROR, e.message ?: "Не удалось запустить сетевой движок")
                stopTunnel(announce = false)
            }
        }, "ForceC-Xray-start").start()
    }

    private fun normalizeConfig(source: JSONObject): JSONObject {
        val config = JSONObject(source.toString())
        val outbounds = config.optJSONArray("outbounds")
            ?: throw IllegalStateException("Xray JSON не содержит outbounds.")
        if (outbounds.length() == 0) throw IllegalStateException("В конфигурации нет серверов.")

        var firstTag = outbounds.optJSONObject(0)?.optString("tag").orEmpty()
        if (firstTag.isBlank()) {
            firstTag = "proxy"
            outbounds.optJSONObject(0)?.put("tag", firstTag)
        }

        config.put("inbounds", JSONArray().put(
            JSONObject()
                .put("tag", "forcec-tun")
                .put("protocol", "tun")
                .put("settings", JSONObject()
                    .put("name", "ForceCLIENT")
                    .put("mtu", 1500)
                    .put("gateway", JSONArray().put("10.111.0.1/30").put("fd00:fc::1/126")))
        ))

        val routing = config.optJSONObject("routing") ?: JSONObject()
        val rules = routing.optJSONArray("rules") ?: JSONArray()
        rules.put(JSONObject()
            .put("type", "field")
            .put("network", "tcp,udp")
            .put("outboundTag", firstTag))
        routing.put("rules", rules)
        config.put("routing", routing)
        if (!config.has("log")) config.put("log", JSONObject().put("loglevel", "warning"))
        return config
    }

    private fun buildConfig(sourceOutbounds: JSONArray): JSONObject {
        val outbounds = JSONArray()
        var firstTag = "proxy"
        for (i in 0 until sourceOutbounds.length()) {
            val item = sourceOutbounds.optJSONObject(i) ?: continue
            val copy = JSONObject(item.toString())
            if (i == 0 && copy.optString("tag").isBlank()) copy.put("tag", firstTag)
            if (i == 0) firstTag = copy.optString("tag", firstTag)
            outbounds.put(copy)
        }
        if (outbounds.length() == 0) throw IllegalStateException("Подписка не содержит поддерживаемых серверов.")
        outbounds.put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
        outbounds.put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        return JSONObject()
            .put("log", JSONObject().put("loglevel", "warning"))
            .put("outbounds", outbounds)
            .put("routing", JSONObject()
                .put("domainStrategy", "AsIs")
                .put("rules", JSONArray().put(
                    JSONObject().put("type", "field").put("network", "tcp,udp").put("outboundTag", firstTag)
                )))
    }

    private fun findCoreClass(): Class<*> {
        val names = listOf("libXray.LibXray", "libxray.LibXray")
        for (name in names) {
            try { return Class.forName(name) } catch (_: ClassNotFoundException) { }
        }
        throw IllegalStateException("Библиотека libXray не найдена в APK. Пересоберите приложение с сетевым движком.")
    }

    private fun invokeCore(core: Class<*>, method: String, payload: JSONObject): JSONObject {
        val request = JSONObject().put("apiVersion", 3).put("method", method).put("payload", payload)
        val invoke = core.methods.firstOrNull { it.name == "invoke" && it.parameterTypes.contentEquals(arrayOf(String::class.java)) }
            ?: throw IllegalStateException("В libXray отсутствует API invoke(String).")
        val response = invoke.invoke(null, request.toString()) as? String
            ?: throw IllegalStateException("libXray вернул пустой ответ.")
        val parsed = JSONObject(response)
        if (!parsed.optBoolean("success", false)) {
            throw IllegalStateException(parsed.optString("error").ifBlank { "Ошибка libXray при $method." })
        }
        return parsed
    }

    private fun registerSocketProtection(core: Class<*>): Any? {
        val controllerClass = runCatching { Class.forName("libXray.DialerController") }.getOrNull() ?: return null
        val controller = Proxy.newProxyInstance(controllerClass.classLoader, arrayOf(controllerClass)) { _, method, args ->
            when (method.name.lowercase()) {
                "protectfd" -> protect((args?.firstOrNull() as? Number)?.toInt() ?: -1)
                "tostring" -> "ForceCLIENT socket protection"
                "hashcode" -> System.identityHashCode(this)
                "equals" -> this === args?.firstOrNull()
                else -> false
            }
        }
        val register = core.methods.firstOrNull {
            it.name == "registerDialerController" && it.parameterTypes.size == 1 &&
                it.parameterTypes[0].isAssignableFrom(controllerClass)
        } ?: throw IllegalStateException("В libXray отсутствует registerDialerController.")
        register.invoke(null, controller)
        return controller
    }

    private fun pauseTunnel() {
        if (tunnel == null || paused) return
        paused = true
        stopping = false
        engineClass?.let { core ->
            runCatching { invokeCore(core, "stopXray", JSONObject()) }
            runCatching {
                core.methods.firstOrNull { it.name.equals("resetDNS", ignoreCase = true) && it.parameterTypes.isEmpty() }?.invoke(null)
            }
        }
        engineClass = null
        runCatching { tunnel?.close() }
        tunnel = null
        broadcastState(STATE_PAUSED, "VPN на паузе")
        updateNotification("VPN на паузе", pausedState = true)
    }

    private fun stopTunnel(announce: Boolean = true) {
        stopping = true
        val core = engineClass
        if (core != null) {
            runCatching { invokeCore(core, "stopXray", JSONObject()) }
            runCatching {
                core.methods.firstOrNull { it.name.equals("resetDNS", ignoreCase = true) && it.parameterTypes.isEmpty() }
                    ?.invoke(null)
            }
        }
        engineClass = null
        runCatching { tunnel?.close() }
        tunnel = null
        if (announce) broadcastState(STATE_DISCONNECTED, "Отключено")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastState(state: String, message: String) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName).putExtra(EXTRA_STATE, state).putExtra(EXTRA_MESSAGE, message))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "ForceCLIENT VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(message: String, connected: Boolean = false, pausedState: Boolean = false): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseIntent = PendingIntent.getService(
            this, 2, Intent(this, ForceVpnService::class.java).setAction(if (pausedState) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val disconnectIntent = PendingIntent.getService(
            this, 3, Intent(this, ForceVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.forceclient.app.R.drawable.ic_forcec_notification)
            .setContentTitle("ForceCLIENT")
            .setContentText(message)
            .apply {
                if (connected || pausedState) {
                    addAction(0, if (pausedState) "Продолжить" else "Пауза", pauseIntent)
                    addAction(0, "Отключить", disconnectIntent)
                }
            }
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String, connected: Boolean = false, pausedState: Boolean = false) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message, connected, pausedState))
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (tunnel != null || engineClass != null) stopTunnel(announce = false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.forceclient.app.vpn.START"
        const val ACTION_STOP = "com.forceclient.app.vpn.STOP"
        const val ACTION_PAUSE = "com.forceclient.app.vpn.PAUSE"
        const val ACTION_RESUME = "com.forceclient.app.vpn.RESUME"
        const val ACTION_STATE = "com.forceclient.app.vpn.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_PAUSED = "paused"
        const val STATE_ERROR = "error"
        private const val CHANNEL_ID = "forcec_vpn"
        private const val NOTIFICATION_ID = 42
    }
}

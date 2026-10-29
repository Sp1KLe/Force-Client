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
    private var engineClass: Class<*>? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> startTunnel()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (tunnel != null) return
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
                val protectController = registerSocketProtection(core)
                if (protectController != null) {
                    runCatching {
                        core.methods.firstOrNull { it.name == "setDNS" && it.parameterTypes.size == 2 }
                            ?.invoke(null, protectController, "1.1.1.1:53")
                    }
                }
                val converted = invokeCore(core, "convertShareLinksToXrayJson", JSONObject().put("text", raw))
                val outbounds = converted.optJSONObject("data")?.optJSONArray("outbounds")
                val config = if (raw.startsWith("{") && runCatching { JSONObject(raw).has("outbounds") }.getOrDefault(false)) {
                    JSONObject(raw)
                } else {
                    if (outbounds == null || outbounds.length() == 0) {
                        throw IllegalStateException(converted.optString("error").ifBlank { "Не удалось разобрать профиль Xray." })
                    }
                    buildConfig(outbounds)
                }

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
                config.getJSONObject("env").put("xray.tun.fd", established.fd)
                val runPayload = JSONObject().put("xrayJson", config.toString())
                val runResponse = AtomicReference<JSONObject?>(null)
                val runFailure = AtomicReference<Throwable?>(null)
                val engineThread = Thread({
                    try {
                        runResponse.set(invokeCore(core, "runXray", runPayload))
                    } catch (failure: Throwable) {
                        runFailure.set(failure)
                    }
                }, "ForceC-Xray-engine")
                engineThread.start()

                var started = false
                val deadline = System.currentTimeMillis() + 15_000L
                while (!stopping && engineThread.isAlive && System.currentTimeMillis() < deadline) {
                    val state = runCatching { invokeCore(core, "getXrayState", JSONObject()) }.getOrNull()
                    if (state?.optJSONObject("data")?.optBoolean("running", false) == true) {
                        started = true
                        break
                    }
                    val failure = runFailure.get()
                    if (failure != null) throw IllegalStateException(failure.message ?: "Xray-core завершился с ошибкой.")
                    val response = runResponse.get()
                    if (response != null && !response.optBoolean("success", false)) {
                        throw IllegalStateException(response.optString("error").ifBlank { "Xray-core отклонил конфигурацию." })
                    }
                    Thread.sleep(250)
                }
                if (!stopping && !started) {
                    val failure = runFailure.get()
                    val response = runResponse.get()
                    throw IllegalStateException(
                        failure?.message ?: response?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "Xray-core не запустился за 15 секунд."
                    )
                }
                if (started) {
                    broadcastState(STATE_CONNECTED, "Xray-core запущен")
                    updateNotification("Защищённое соединение активно")
                }
                while (!stopping && engineThread.isAlive) Thread.sleep(500)
                if (!stopping) {
                    val response = runResponse.get()
                    throw IllegalStateException(response?.optString("error")?.takeIf { it.isNotBlank() }
                        ?: "Сетевой движок остановился.")
                }
            } catch (e: Exception) {
                broadcastState(STATE_ERROR, e.message ?: "Не удалось запустить сетевой движок")
                stopTunnel(announce = false)
            }
        }, "ForceC-Xray-start").start()
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
        if (!parsed.optBoolean("success", false) && method != "runXray") {
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
        }
        register?.invoke(null, controller)
        return controller
    }

    private fun stopTunnel(announce: Boolean = true) {
        stopping = true
        val core = engineClass
        if (core != null) {
            runCatching { invokeCore(core, "stopXray", JSONObject()) }
        }
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

    private fun notification(message: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("ForceCLIENT")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(message))
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.forceclient.app.vpn.START"
        const val ACTION_STOP = "com.forceclient.app.vpn.STOP"
        const val ACTION_STATE = "com.forceclient.app.vpn.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        const val STATE_CONNECTED = "connected"
        const val STATE_DISCONNECTED = "disconnected"
        const val STATE_ERROR = "error"
        private const val CHANNEL_ID = "forcec_vpn"
        private const val NOTIFICATION_ID = 42
    }
}

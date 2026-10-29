package com.forceclient.app

import org.json.JSONObject

/**
 * Small reflection-based adapter for the official gomobile libXray AAR.
 *
 * Reflection keeps this app source compilable even if upstream changes the
 * generated Java package name. At runtime we fail clearly if the AAR/API is
 * missing rather than pretending a core is running.
 */
object XrayEngine {
    private val classCandidates = listOf(
        "libXray.LibXray",
        "libxray.LibXray",
        "libXray.Libxray",
        "LibXray.LibXray"
    )

    private fun invoke(request: JSONObject): JSONObject {
        val errors = mutableListOf<String>()
        for (name in classCandidates) {
            try {
                val bridge = Class.forName(name)
                val method = bridge.methods.firstOrNull {
                    it.name.equals("invoke", ignoreCase = true) &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == String::class.java
                }
                if (method == null) {
                    errors += "$name: invoke(String) not found"
                    continue
                }
                val raw = method.invoke(null, request.toString()) as? String
                    ?: throw IllegalStateException("libXray returned an empty response")
                val response = JSONObject(raw)
                if (!response.optBoolean("success", false)) {
                    throw IllegalStateException(response.optString("error", "Xray-core request failed"))
                }
                return response
            } catch (e: ClassNotFoundException) {
                errors += "$name: class not found"
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                throw IllegalStateException(cause.message ?: "Xray-core request failed", cause)
            }
        }
        throw IllegalStateException(
            "libXray is not available. Check that the official AAR was packaged. " +
                errors.joinToString("; ")
        )
    }

    fun version(): String {
        val response = invoke(request("xrayVersion"))
        val data = response.optJSONObject("data") ?: JSONObject()
        return data.optString("version", data.toString())
    }

    /** Convert VLESS/VMess/Trojan/Shadowsocks share links into Xray outbounds. */
    fun convertShareLinks(text: String): JSONObject {
        require(text.isNotBlank()) { "No configuration text supplied" }
        val response = invoke(
            request("convertShareLinksToXrayJson", JSONObject().put("text", text))
        )
        val data = response.optJSONObject("data")
            ?: throw IllegalStateException("Xray-core returned no converted configuration")
        val outbounds = data.optJSONArray("outbounds")
            ?: throw IllegalStateException("No supported outbound was found in this subscription")
        require(outbounds.length() > 0) { "No supported VLESS/VMess/Trojan/Shadowsocks nodes found" }
        return JSONObject().put("outbounds", outbounds)
    }

    data class ProfilePing(
        val tag: String,
        val host: String,
        val port: Int,
        val delayMs: Int?,
        val success: Boolean,
        val error: String? = null,
        val outbound: JSONObject
    )

    data class FastestProfile(
        val outbound: JSONObject,
        val tag: String,
        val host: String,
        val port: Int,
        val delayMs: Int
    )

    /**
     * Uses libXray's pingBatch, which measures a real HTTP request through each
     * Xray outbound. It is not a TCP-port-only test. API accepts up to 5 nodes
     * per request, so larger subscriptions are checked in batches.
     */
    fun pingProfiles(text: String): List<ProfilePing> {
        val outbounds = loadOutbounds(text)
        require(outbounds.length() > 0) { "В подписке не найдено серверов для проверки." }
        normalizeTags(outbounds)
        val configJson = JSONObject().put("outbounds", outbounds).toString()
        val result = mutableListOf<ProfilePing>()

        var start = 0
        while (start < outbounds.length()) {
            val batch = org.json.JSONArray()
            val indexes = mutableListOf<Int>()
            val end = minOf(start + 5, outbounds.length())
            for (i in start until end) {
                val outbound = outbounds.optJSONObject(i) ?: continue
                val tag = outbound.optString("tag")
                batch.put(JSONObject().put("xrayJson", configJson).put("outboundTag", tag))
                indexes += i
            }
            val response = invoke(request(
                "pingBatch",
                JSONObject()
                    .put("configs", batch)
                    .put("timeout", 5)
                    .put("url", "https://cp.cloudflare.com/")
            ))
            val data = response.optJSONObject("data")
                ?: throw IllegalStateException("Xray не вернул результаты проверки узлов.")
            val results = data.optJSONArray("results")
                ?: throw IllegalStateException("Xray вернул пустой список результатов pingBatch.")
            for (j in indexes.indices) {
                val outbound = outbounds.optJSONObject(indexes[j]) ?: continue
                val item = results.optJSONObject(j) ?: JSONObject()
                val success = item.optBoolean("success", false)
                val delay = item.optLong("delay", 10000L)
                val endpoint = endpointOf(outbound)
                result += ProfilePing(
                    tag = outbound.optString("tag"),
                    host = endpoint.first,
                    port = endpoint.second,
                    delayMs = if (success && delay in 1L..9999L) delay.toInt() else null,
                    success = success && delay < 10000L,
                    error = item.optString("error").takeIf { it.isNotBlank() },
                    outbound = JSONObject(outbound.toString())
                )
            }
            start = end
        }
        return result
    }

    fun selectFastestProfile(text: String): FastestProfile {
        val best = pingProfiles(text)
            .filter { it.success && it.delayMs != null }
            .minByOrNull { it.delayMs!! }
            ?: throw IllegalStateException("Ни один узел не прошёл проверку Xray ping. Проверьте подписку или доступ к серверам.")
        return FastestProfile(best.outbound, best.tag, best.host, best.port, best.delayMs!!)
    }

    private fun loadOutbounds(text: String): org.json.JSONArray {
        require(text.isNotBlank()) { "Конфигурация пустая." }
        if (text.trimStart().startsWith("{")) {
            val root = runCatching { JSONObject(text) }.getOrNull()
            val existing = root?.optJSONArray("outbounds")
            if (existing != null && existing.length() > 0) {
                val copy = org.json.JSONArray()
                for (i in 0 until existing.length()) {
                    existing.optJSONObject(i)?.let { copy.put(JSONObject(it.toString())) }
                }
                if (copy.length() > 0) return copy
            }
        }
        val converted = convertShareLinks(text)
        return converted.optJSONArray("outbounds")
            ?: throw IllegalStateException("Не удалось разобрать узлы подписки.")
    }

    private fun normalizeTags(outbounds: org.json.JSONArray) {
        val used = mutableSetOf<String>()
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            var tag = outbound.optString("tag").trim()
            if (tag.isBlank() || used.contains(tag)) {
                tag = "forcec-node-$i"
                while (used.contains(tag)) tag += "-x"
                outbound.put("tag", tag)
            }
            used += tag
        }
    }

    private fun endpointOf(outbound: JSONObject): Pair<String, Int> {
        val settings = outbound.optJSONObject("settings") ?: JSONObject()
        val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
        val server = settings.optJSONArray("servers")?.optJSONObject(0)
        val host = vnext?.optString("address").orEmpty().ifBlank {
            server?.optString("address").orEmpty()
        }
        val port = (vnext?.optInt("port", 0) ?: 0).takeIf { it > 0 }
            ?: (server?.optInt("port", 0) ?: 0)
        return host to port
    }

    /** Validate an Xray JSON configuration through the core's validation API. */
    fun validate(configJson: String): JSONObject {
        require(configJson.isNotBlank()) { "Configuration is empty" }
        val config = JSONObject(configJson)
        require(config.optJSONArray("outbounds")?.length()?.let { it > 0 } == true) {
            "Xray configuration must contain at least one outbound"
        }
        return invoke(request("testXray", JSONObject().put("xrayJson", config.toString())))
    }

    /** Starts the Xray core instance. This alone does NOT create Android's VPN TUN. */
    fun start(configJson: String): JSONObject {
        require(configJson.isNotBlank()) { "Configuration is empty" }
        val config = JSONObject(configJson)
        require(config.optJSONArray("outbounds")?.length()?.let { it > 0 } == true) {
            "Xray configuration must contain at least one outbound"
        }
        return invoke(request("runXray", JSONObject().put("xrayJson", config.toString())))
    }

    fun stop(): JSONObject = invoke(request("stopXray"))

    fun state(): JSONObject = invoke(request("getXrayState"))

    private fun request(method: String, payload: JSONObject = JSONObject()): JSONObject =
        JSONObject()
            .put("apiVersion", 3)
            .put("method", method)
            .put("payload", payload)
}

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

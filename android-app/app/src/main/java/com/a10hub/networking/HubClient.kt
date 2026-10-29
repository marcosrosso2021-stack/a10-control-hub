package com.a10hub.networking

import android.content.Context
import okhttp3.*
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

enum class ConnState { DISCONNECTED, CONNECTING, PAIRING, READY, ERROR }

/** Stockage léger : hôte, port, jeton, empreinte épinglée, identifiant d'appareil. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("hub", Context.MODE_PRIVATE)
    var host: String get() = p.getString("host", "") ?: ""; set(v) = p.edit().putString("host", v).apply()
    var port: Int get() = p.getInt("port", 8765); set(v) = p.edit().putInt("port", v).apply()
    var token: String get() = p.getString("token", "") ?: ""; set(v) = p.edit().putString("token", v).apply()
    var fingerprint: String get() = p.getString("fp", "") ?: ""; set(v) = p.edit().putString("fp", v).apply()
    var pcName: String get() = p.getString("pc", "") ?: ""; set(v) = p.edit().putString("pc", v).apply()
    val deviceId: String get() = p.getString("did", null) ?: UUID.randomUUID().toString().also { p.edit().putString("did", it).apply() }
    fun forget() = p.edit().remove("token").remove("fp").remove("pc").apply()
}

/**
 * Client WebSocket sécurisé (wss). Aucune autorité de certification n'est utilisée :
 * le certificat du PC est épinglé par son empreinte SHA-256.
 *  - Appairage : empreinte vue pendant la connexion + preuve HMAC(code, empreinte) ;
 *    si un intermédiaire présente un autre certificat, le serveur refuse la preuve.
 *  - Sessions suivantes : l'empreinte stockée doit correspondre, sinon la connexion échoue.
 */
class HubClient(private val store: Store, private val onState: (ConnState, String) -> Unit) {
    private var ws: WebSocket? = null
    @Volatile private var seenFp = ""
    @Volatile var state = ConnState.DISCONNECTED; private set

    private fun set(s: ConnState, msg: String = "") { state = s; onState(s, msg) }

    private fun sha256Hex(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun client(pinned: String?): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, a: String) {
                val fp = sha256Hex(chain[0].encoded)
                seenFp = fp
                if (pinned != null && !fp.equals(pinned, ignoreCase = true)) throw java.security.cert.CertificateException("Empreinte inattendue")
            }
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // l'épinglage d'empreinte remplace la vérification de nom
            .pingInterval(java.time.Duration.ofSeconds(15))
            .build()
    }

    private fun send(type: String, vararg extra: Pair<String, Any>) {
        val o = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString().take(8)).put("type", type)
        extra.forEach { o.put(it.first, it.second) }
        ws?.send(o.toString())
    }

    fun connect(pairingCode: String? = null) {
        disconnect()
        val pairing = pairingCode != null
        val pinned = if (pairing) null else store.fingerprint.ifEmpty { null }
        if (!pairing && store.token.isEmpty()) { set(ConnState.ERROR, "Appareil non appairé"); return }
        set(if (pairing) ConnState.PAIRING else ConnState.CONNECTING)
        val req = Request.Builder().url("wss://${store.host}:${store.port}/ws").build()
        ws = client(pinned).newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (pairing) {
                    val proof = Mac.getInstance("HmacSHA256").run {
                        init(SecretKeySpec(pairingCode!!.toByteArray(), "HmacSHA256"))
                        doFinal(seenFp.toByteArray()).joinToString("") { "%02x".format(it) }
                    }
                    send("pair", "device_id" to store.deviceId, "device_name" to android.os.Build.MODEL, "proof" to proof)
                } else {
                    send("hello", "device_id" to store.deviceId, "token" to store.token)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = JSONObject(text)
                when (m.optString("type")) {
                    "paired" -> {
                        store.token = m.getString("token"); store.fingerprint = seenFp; store.pcName = m.optString("pc_name")
                        set(ConnState.READY, store.pcName)
                    }
                    "ready" -> set(ConnState.READY, m.optString("pc_name"))
                    "error" -> set(ConnState.ERROR, m.optString("code"))
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = set(ConnState.ERROR, t.message ?: "Connexion perdue")
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = set(ConnState.DISCONNECTED)
        })
    }

    fun disconnect() { ws?.close(1000, null); ws = null; if (state != ConnState.ERROR) set(ConnState.DISCONNECTED) }

    fun move(dx: Int, dy: Int) { if (state == ConnState.READY && (dx != 0 || dy != 0)) send("mouse_move", "dx" to dx, "dy" to dy) }
    fun button(button: String, action: String) { if (state == ConnState.READY) send("mouse_button", "button" to button, "action" to action) }
    fun scroll(dy: Int, dx: Int = 0) { if (state == ConnState.READY && (dx != 0 || dy != 0)) send("scroll", "dx" to dx, "dy" to dy) }
}

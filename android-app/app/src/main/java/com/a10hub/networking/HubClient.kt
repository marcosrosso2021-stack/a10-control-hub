package com.a10hub.networking

import android.content.Context
import okhttp3.*
import org.json.JSONArray
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

/** Action autorisée annoncée par le PC (identifiant + libellé ; rien d'exécutable ne transite). */
data class ActionItem(val id: String, val label: String, val sensitive: Boolean, val page: String)

data class DiskInfo(val mount: String, val percent: Double, val freeGb: Double, val totalGb: Double)
data class ProcInfo(val name: String, val cpu: Double, val memMb: Int)

/** Instantané des statistiques du PC. Batterie et température sont nulles si le PC n'en expose pas. */
data class PcStats(
    val cpu: Double, val cores: Int,
    val memPercent: Double, val memUsedMb: Int, val memTotalMb: Int,
    val disks: List<DiskInfo>,
    val upKbps: Double, val downKbps: Double,
    val batteryPercent: Double?, val batteryPlugged: Boolean,
    val tempC: Double?, val uptimeS: Long,
    val top: List<ProcInfo>,
)

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

    /** Rappels appelés depuis un thread réseau : l'appelant doit repasser sur le thread UI. */
    @Volatile var onActions: ((List<ActionItem>) -> Unit)? = null
    @Volatile var onActionResult: ((actionId: String, code: String) -> Unit)? = null
    @Volatile var onStats: ((PcStats) -> Unit)? = null
    @Volatile var onStatsError: ((code: String) -> Unit)? = null

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
                        send("actions_list")
                    }
                    "ready" -> { set(ConnState.READY, m.optString("pc_name")); send("actions_list") }
                    "actions" -> {
                        val arr = m.optJSONArray("items") ?: JSONArray()
                        onActions?.invoke((0 until arr.length()).map {
                            val o = arr.getJSONObject(it)
                            ActionItem(o.getString("id"), o.getString("label"), o.optBoolean("sensitive"), o.optString("page", "Actions"))
                        })
                    }
                    "action_result" -> onActionResult?.invoke(m.optString("action_id"), m.optString("code"))
                    "stats" -> onStats?.invoke(parseStats(m))
                    "error" -> {
                        // Une erreur sur un message isolé (ex. touche refusée) ne doit pas couper la session
                        val code = m.optString("code")
                        if (code == "stats_unavailable") onStatsError?.invoke(code)
                        if (code in setOf("auth_failed", "pair_failed", "unauthenticated")) set(ConnState.ERROR, code)
                    }
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

    /** Appui de touche ; [mods] parmi ctrl, alt, shift, win. */
    fun key(key: String, mods: List<String> = emptyList()) {
        if (state == ConnState.READY) send("key", "key" to key, "mods" to JSONArray(mods), "action" to "press")
    }

    /** Saisie de texte Unicode, découpée en morceaux de 256 caractères (limite du protocole). */
    fun text(s: String) {
        if (state != ConnState.READY || s.isEmpty()) return
        s.chunked(256).forEach { send("text", "text" to it) }
    }

    /** Demande ponctuelle : le téléphone décide de la fréquence (économie de batterie et de réseau). */
    fun requestStats(top: Int = 5) {
        if (state == ConnState.READY) send("stats_request", "top" to top)
    }

    private fun parseStats(m: JSONObject): PcStats {
        val mem = m.getJSONObject("mem")
        val net = m.getJSONObject("net")
        val battery = m.optJSONObject("battery")
        val disks = m.optJSONArray("disks") ?: JSONArray()
        val top = m.optJSONArray("top") ?: JSONArray()
        return PcStats(
            cpu = m.getDouble("cpu"), cores = m.optInt("cores", 1),
            memPercent = mem.getDouble("percent"), memUsedMb = mem.getInt("used_mb"), memTotalMb = mem.getInt("total_mb"),
            disks = (0 until disks.length()).map {
                val d = disks.getJSONObject(it)
                DiskInfo(d.getString("mount"), d.getDouble("percent"), d.getDouble("free_gb"), d.getDouble("total_gb"))
            },
            upKbps = net.getDouble("up_kbps"), downKbps = net.getDouble("down_kbps"),
            batteryPercent = battery?.getDouble("percent"), batteryPlugged = battery?.optBoolean("plugged") ?: false,
            tempC = if (m.has("temp_c") && !m.isNull("temp_c")) m.getDouble("temp_c") else null,
            uptimeS = m.optLong("uptime_s"),
            top = (0 until top.length()).map {
                val p = top.getJSONObject(it)
                ProcInfo(p.getString("name"), p.getDouble("cpu"), p.getInt("mem_mb"))
            },
        )
    }

    fun runAction(id: String, confirmed: Boolean = false) {
        if (state == ConnState.READY) send("action", "action_id" to id, "confirmed" to confirmed)
    }
}

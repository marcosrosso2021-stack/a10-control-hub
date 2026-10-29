package com.a10hub

import android.os.BatteryManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.a10hub.airmouse.AirMouseScreen
import com.a10hub.keyboard.KeyboardScreen
import com.a10hub.macros.CommandPanel
import com.a10hub.networking.ActionItem
import com.a10hub.monitoring.MonitoringScreen
import com.a10hub.networking.ConnState
import com.a10hub.networking.HubClient
import com.a10hub.networking.PcStats
import com.a10hub.networking.Store
import com.a10hub.touchpad.Touchpad
import com.a10hub.touchpad.TouchpadSettings

private val Bg = Color(0xFF0A1022)
private val Accent = Color(0xFF2F6BFF)
private val Ok = Color(0xFF2ECC71)
private val Warn = Color(0xFFFF8A3D)

class MainActivity : ComponentActivity() {
    private lateinit var client: HubClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = Store(this)
        var stateHolder by mutableStateOf(ConnState.DISCONNECTED to "")
        client = HubClient(store) { s, m -> runOnUiThread { stateHolder = s to m } }
        var actions by mutableStateOf(emptyList<ActionItem>())
        var lastResult by mutableStateOf("")
        var stats by mutableStateOf<PcStats?>(null)
        var statsUnavailable by mutableStateOf(false)
        client.onStats = { s -> runOnUiThread { stats = s; statsUnavailable = false } }
        client.onStatsError = { runOnUiThread { statsUnavailable = true } }
        client.onActions = { list -> runOnUiThread { actions = list } }
        client.onActionResult = { id, code ->
            runOnUiThread {
                val label = actions.firstOrNull { it.id == id }?.label ?: id
                lastResult = when (code) {
                    "done" -> "✓ $label"
                    "confirmation_required" -> "Confirmation requise : $label"
                    "unknown_action" -> "Action inconnue : $label"
                    else -> "Échec : $label"
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Bg)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    val (state, msg) = stateHolder
                    if (state == ConnState.READY) HubScreen(client, store, state, actions, lastResult, stats, statsUnavailable)
                    else ConnectScreen(store, state, msg, client)
                }
            }
        }
    }

    override fun onStop() { super.onStop(); client.disconnect() }

    override fun onStart() {
        super.onStart()
        val s = Store(this)
        if (s.token.isNotEmpty() && s.host.isNotEmpty()) client.connect() // reconnexion automatique
    }
}

@Composable
private fun ConnectScreen(store: Store, state: ConnState, msg: String, client: HubClient) {
    var host by remember { mutableStateOf(store.host) }
    var port by remember { mutableStateOf(store.port.toString()) }
    var code by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("A10 Control Hub", style = MaterialTheme.typography.headlineMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            when (state) {
                ConnState.CONNECTING, ConnState.PAIRING -> "Connexion…"
                ConnState.ERROR -> "Erreur : $msg"
                else -> if (store.token.isEmpty()) "Entrez l'adresse du PC et le code affiché par l'agent." else "Déconnecté"
            },
            color = if (state == ConnState.ERROR) Warn else Color.LightGray
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(host, { host = it.trim() }, label = { Text("Adresse IP du PC") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(code, { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code d'appairage (6 chiffres)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { store.host = host; store.port = port.toIntOrNull() ?: 8765; client.connect(code) },
            enabled = host.isNotEmpty() && code.length == 6,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("Appairer") }
        if (store.token.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { store.host = host; client.connect() }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Se reconnecter") }
            TextButton(onClick = { store.forget() }) { Text("Oublier ce PC", color = Warn) }
        }
    }
}

@Composable
private fun HubScreen(
    client: HubClient, store: Store, state: ConnState, actions: List<ActionItem>, lastResult: String,
    stats: PcStats?, statsUnavailable: Boolean,
) {
    val settingsState = remember { mutableStateOf(TouchpadSettings()) }
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Ok, androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(8.dp))
            Text("Connecté à ${store.pcName}", color = Color.White, modifier = Modifier.weight(1f))
            TextButton(onClick = { client.disconnect() }) { Text("Quitter") }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            listOf("Souris", "Air Mouse", "Clavier", "Commandes", "Moniteur").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (tab) {
            0 -> MouseTab(client, settingsState, Modifier.weight(1f).fillMaxWidth())
            1 -> AirMouseScreen(client, Modifier.weight(1f).fillMaxWidth())
            2 -> KeyboardScreen(client, Modifier.weight(1f).fillMaxWidth())
            3 -> CommandPanel(client, actions, lastResult, Modifier.weight(1f).fillMaxWidth())
            else -> MonitoringScreen(client, stats, statsUnavailable, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun MouseTab(client: HubClient, settingsState: MutableState<TouchpadSettings>, modifier: Modifier) {
    var settings by settingsState
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp)) { Touchpad(client, settings) }
        Row(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val order = if (settings.leftHanded) listOf("right", "left") else listOf("left", "right")
            order.forEach { b ->
                Button(onClick = { client.button(b, "click") }, modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Text(if (b == "left") "Clic gauche" else "Clic droit")
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Sensibilité", color = Color.LightGray)
        Slider(settings.sensitivity, { settings = settings.copy(sensitivity = it) }, valueRange = 0.5f..4f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(settings.invertScroll, { settings = settings.copy(invertScroll = it) }); Spacer(Modifier.width(8.dp)); Text("Inverser défilement", color = Color.White)
            Spacer(Modifier.width(16.dp))
            Switch(settings.leftHanded, { settings = settings.copy(leftHanded = it) }); Spacer(Modifier.width(8.dp)); Text("Gaucher", color = Color.White)
        }
    }
}

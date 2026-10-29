package com.a10hub.monitoring

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.a10hub.networking.HubClient
import com.a10hub.networking.PcStats
import kotlinx.coroutines.delay
import java.util.Locale

private val Accent = Color(0xFF2F6BFF)
private val Idle = Color(0xFF1B2540)
private val CardBg = Color(0xFF111A2E)
private val Good = Color(0xFF2ECC71)
private val Warn = Color(0xFFFF8A3D)
private val Bad = Color(0xFFE74C3C)

private fun levelColor(percent: Double) = when {
    percent < 60 -> Good
    percent < 85 -> Warn
    else -> Bad
}

private fun fmt(v: Double, digits: Int = 1) = String.format(Locale.FRANCE, "%.${digits}f", v)

private fun fmtRate(kbps: Double) = if (kbps >= 1024) "${fmt(kbps / 1024)} Mo/s" else "${fmt(kbps, 0)} Ko/s"

private fun fmtUptime(s: Long): String {
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
    return if (d > 0) "$d j $h h" else if (h > 0) "$h h $m min" else "$m min"
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = CardBg, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = Color.LightGray, style = MaterialTheme.typography.labelLarge)
            content()
        }
    }
}

@Composable
private fun Meter(percent: Double) {
    LinearProgressIndicator(
        progress = { (percent / 100.0).toFloat().coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = levelColor(percent),
        trackColor = Idle,
    )
}

/**
 * Moniteur du PC. Le téléphone n'interroge le PC que tant que cet écran est affiché
 * (la boucle s'arrête quand on change d'onglet) : pas de trafic ni de batterie gaspillés.
 */
@Composable
fun MonitoringScreen(client: HubClient, stats: PcStats?, unavailable: Boolean, modifier: Modifier = Modifier) {
    var intervalS by remember { mutableIntStateOf(2) }

    LaunchedEffect(intervalS) {
        while (true) {
            client.requestStats(5)
            delay(intervalS * 1000L)
        }
    }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("Rafraîchir toutes les", color = Color.LightGray)
            listOf(1, 2, 5).forEach { s ->
                Button(
                    onClick = { intervalS = s },
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (s == intervalS) Accent else Idle),
                ) { Text("$s s") }
            }
        }

        if (unavailable) {
            Text(
                "Le module de statistiques n'est pas disponible sur le PC. Dans PowerShell (environnement activé) : pip install psutil, puis relancez l'agent.",
                color = Warn,
            )
        } else if (stats == null) {
            Text("Chargement…", color = Color.LightGray)
        }

        stats?.let { s ->
            Section("Processeur (${s.cores} cœurs logiques)") {
                Text("${fmt(s.cpu)} %", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                Meter(s.cpu)
                s.tempC?.let { Text("Température : ${fmt(it)} °C", color = Color.LightGray) }
            }
            Section("Mémoire") {
                Text(
                    "${fmt(s.memPercent)} %  ·  ${fmt(s.memUsedMb / 1024.0)} / ${fmt(s.memTotalMb / 1024.0)} Go",
                    color = Color.White,
                )
                Meter(s.memPercent)
            }
            if (s.disks.isNotEmpty()) {
                Section("Disques") {
                    s.disks.forEach { d ->
                        Text("${d.mount}  ·  ${fmt(d.freeGb)} Go libres sur ${fmt(d.totalGb)} Go", color = Color.White)
                        Meter(d.percent)
                    }
                }
            }
            Section("Réseau") {
                Text("↓ ${fmtRate(s.downKbps)}     ↑ ${fmtRate(s.upKbps)}", color = Color.White)
            }
            s.batteryPercent?.let { b ->
                Section("Batterie du PC") {
                    Text("${fmt(b, 0)} %  ·  ${if (s.batteryPlugged) "sur secteur" else "sur batterie"}", color = Color.White)
                    Meter(100 - b)   // la barre se remplit (et rougit) quand la batterie baisse
                }
            }
            if (s.top.isNotEmpty()) {
                Section("Processus principaux") {
                    s.top.forEach { p ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(p.name, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("${fmt(p.cpu)} %  ·  ${p.memMb} Mo", color = Color.LightGray)
                        }
                    }
                }
            }
            Text("PC allumé depuis ${fmtUptime(s.uptimeS)}", color = Color.LightGray)
        }
        Spacer(Modifier.height(12.dp))
    }
}

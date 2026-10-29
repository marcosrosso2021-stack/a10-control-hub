package com.a10hub.airmouse

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.a10hub.networking.HubClient

private val Accent = Color(0xFF2F6BFF)
private val Idle = Color(0xFF1B2540)
private val Warn = Color(0xFFFF8A3D)
private val Ok = Color(0xFF2ECC71)

/**
 * Air Mouse : le curseur se déplace vers le bord abaissé du téléphone.
 * L'accéléromètre n'est écouté QUE lorsque cet écran est affiché, activé et non en pause
 * (économie de batterie, et aucun mouvement involontaire en dehors de cet écran).
 */
@Composable
fun AirMouseScreen(client: HubClient, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sm = remember { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember { sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }
    val math = remember { AirMouseMath() }

    var active by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var calibrating by remember { mutableStateOf(false) }
    var calibrated by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(AirMouseSettings()) }
    val settingsNow = rememberUpdatedState(settings)
    val holdingNow = rememberUpdatedState(holding)

    // Le capteur n'est enregistré que si l'Air Mouse est actif et non en pause.
    DisposableEffect(active, paused) {
        if (!active || paused || sensor == null) return@DisposableEffect onDispose { }
        math.reset()
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val (dx, dy) = math.update(e.values[0], e.values[1], e.values[2], settingsNow.value)
                if (calibrating && !math.calibrating && math.isCalibrated) { calibrating = false; calibrated = true }
                if (dx != 0 || dy != 0) client.move(dx, dy)
            }
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    // En quittant l'écran, on relâche un éventuel « glisser » resté enfoncé.
    DisposableEffect(Unit) { onDispose { if (holdingNow.value) client.button("left", "up") } }

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sensor == null) {
            Text("Ce téléphone n'a pas d'accéléromètre : l'Air Mouse n'est pas disponible.", color = Warn)
            return@Column
        }

        Text(
            when {
                !active -> "1. Activez l'Air Mouse. 2. Tenez le téléphone dans la position qui sera votre « neutre ». 3. Touchez Calibrer et ne bougez plus pendant une seconde. 4. Inclinez : le curseur va vers le bord abaissé, plus vite quand l'inclinaison augmente."
                calibrating -> "Calibration… ne bougez plus."
                paused -> "En pause : le curseur ne bouge plus."
                !calibrated -> "Actif. Touchez Calibrer, téléphone immobile dans la position neutre."
                else -> "Actif et calibré. Inclinez pour déplacer le curseur."
            },
            color = if (active && calibrated && !paused) Ok else Color.LightGray,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    if (active && holding) { client.button("left", "up"); holding = false }
                    active = !active; paused = false; calibrating = false
                    if (!active) calibrated = false
                },
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (active) Warn else Accent),
            ) { Text(if (active) "Désactiver" else "Activer") }
            Button(
                onClick = { math.startCalibration(); calibrating = true; calibrated = false },
                enabled = active && !paused,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Idle),
            ) { Text(if (calibrated) "Recentrer" else "Calibrer") }
            Button(
                onClick = { paused = !paused },
                enabled = active,
                modifier = Modifier.weight(1f).height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (paused) Accent else Idle),
            ) { Text(if (paused) "Reprendre" else "Pause") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { client.button("left", "click") }, modifier = Modifier.weight(1f).height(96.dp)) { Text("Clic gauche") }
            Button(onClick = { client.button("right", "click") }, modifier = Modifier.weight(1f).height(96.dp)) { Text("Clic droit") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(holding, {
                holding = it
                client.button("left", if (it) "down" else "up")   // glisser-déposer : bouton gauche maintenu
            })
            Spacer(Modifier.width(8.dp))
            Text("Maintenir le clic gauche (glisser)", color = Color.White)
        }

        Text("Sensibilité", color = Color.LightGray)
        Slider(settings.sensitivity, { settings = settings.copy(sensitivity = it) }, valueRange = 0.3f..3f)
        Text("Zone morte (immobile en dessous de cette inclinaison)", color = Color.LightGray)
        Slider(settings.deadZone, { settings = settings.copy(deadZone = it) }, valueRange = 0.02f..0.15f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(settings.invertH, { settings = settings.copy(invertH = it) })
            Spacer(Modifier.width(8.dp)); Text("Inverser gauche/droite", color = Color.White)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(settings.invertV, { settings = settings.copy(invertV = it) })
            Spacer(Modifier.width(8.dp)); Text("Inverser haut/bas", color = Color.White)
        }
        Text(
            "Mode expérimental : il repose sur l'inclinaison (pas de suivi spatial). Marcher ou secouer le téléphone perturbe la mesure ; les secousses fortes sont ignorées.",
            color = Color.LightGray,
        )
        Spacer(Modifier.height(12.dp))
    }
}

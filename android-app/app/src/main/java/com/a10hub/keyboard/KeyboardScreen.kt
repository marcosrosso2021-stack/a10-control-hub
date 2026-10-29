package com.a10hub.keyboard

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.a10hub.networking.HubClient

private val Accent = Color(0xFF2F6BFF)
private val Idle = Color(0xFF1B2540)
private val MOD_ORDER = listOf("ctrl", "alt", "shift", "win")

/** Envoie la différence entre l'ancien et le nouveau texte (retours arrière + nouveaux caractères). */
internal fun sendDiff(client: HubClient, old: String, new: String) {
    var p = 0
    while (p < old.length && p < new.length && old[p] == new[p]) p++
    repeat(old.length - p) { client.key("backspace") }
    if (new.length > p) client.text(new.substring(p))
}

@Composable
private fun RowScope.KeyBtn(label: String, on: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(48.dp),
        contentPadding = PaddingValues(2.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) Accent else Idle),
    ) { Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge) }
}

/**
 * Clavier complet : saisie de texte (Unicode, donc indépendante AZERTY/QWERTY), touches spéciales,
 * modificateurs « collants » (Ctrl/Alt/Maj/Win valables pour la prochaine touche), F1–F12,
 * raccourcis usuels et pavé numérique.
 */
@Composable
fun KeyboardScreen(client: HubClient, modifier: Modifier = Modifier) {
    var mods by remember { mutableStateOf(setOf<String>()) }
    var text by remember { mutableStateOf("") }
    var direct by remember { mutableStateOf(false) }

    fun press(key: String) {
        client.key(key, MOD_ORDER.filter { it in mods })
        mods = emptySet()
    }
    fun combo(key: String, vararg m: String) = client.key(key, m.toList())

    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(direct, { direct = it; text = "" })
            Spacer(Modifier.width(8.dp))
            Text(if (direct) "Saisie directe (chaque lettre part tout de suite)" else "Saisie puis envoi", color = Color.White)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { new -> if (direct) sendDiff(client, text, new); text = new },
            label = { Text("Texte à écrire sur le PC") },
            modifier = Modifier.fillMaxWidth(),
            minLines = if (direct) 1 else 2,
        )
        if (!direct) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyBtn("Envoyer") { client.text(text); text = "" }
                KeyBtn("Envoyer + Entrée") { client.text(text); client.key("enter"); text = "" }
            }
        }

        Text("Modificateurs (pour la touche suivante)", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ctrl" to "Ctrl", "alt" to "Alt", "shift" to "Maj", "win" to "Win").forEach { (id, label) ->
                KeyBtn(label, on = id in mods) { mods = if (id in mods) mods - id else mods + id }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBtn("Entrée") { press("enter") }
            KeyBtn("Échap") { press("escape") }
            KeyBtn("Tab") { press("tab") }
            KeyBtn("⌫") { press("backspace") }
            KeyBtn("Suppr") { press("delete") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBtn("Espace") { press("space") }
            KeyBtn("←") { press("left") }
            KeyBtn("↑") { press("up") }
            KeyBtn("↓") { press("down") }
            KeyBtn("→") { press("right") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBtn("Début") { press("home") }
            KeyBtn("Fin") { press("end") }
            KeyBtn("Pg ↑") { press("pageup") }
            KeyBtn("Pg ↓") { press("pagedown") }
        }

        Text("Touches de fonction", color = Color.LightGray)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..12).forEach { n ->
                Button(onClick = { press("f$n") }, modifier = Modifier.width(64.dp).height(48.dp),
                    contentPadding = PaddingValues(2.dp), colors = ButtonDefaults.buttonColors(containerColor = Idle)) { Text("F$n") }
            }
        }

        Text("Raccourcis", color = Color.LightGray)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBtn("Copier") { combo("c", "ctrl") }
            KeyBtn("Coller") { combo("v", "ctrl") }
            KeyBtn("Couper") { combo("x", "ctrl") }
            KeyBtn("Annuler") { combo("z", "ctrl") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            KeyBtn("Tout") { combo("a", "ctrl") }
            KeyBtn("Enreg.") { combo("s", "ctrl") }
            KeyBtn("Alt+Tab") { combo("tab", "alt") }
            KeyBtn("Bureau") { combo("d", "win") }
        }

        Text("Pavé numérique", color = Color.LightGray)
        listOf(listOf("7", "8", "9", "/"), listOf("4", "5", "6", "*"), listOf("1", "2", "3", "-"), listOf("0", ".", ",", "+")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { row.forEach { c -> KeyBtn(c) { client.text(c) } } }
        }
        Spacer(Modifier.height(12.dp))
    }
}

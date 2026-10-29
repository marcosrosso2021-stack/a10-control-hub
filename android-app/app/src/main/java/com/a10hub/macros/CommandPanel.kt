package com.a10hub.macros

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.a10hub.networking.ActionItem
import com.a10hub.networking.HubClient

private val Accent = Color(0xFF2F6BFF)
private val Idle = Color(0xFF1B2540)
private val Warn = Color(0xFFFF8A3D)

/**
 * Panneau de commandes façon Stream Deck. La liste des boutons vient du PC (actions.json) ;
 * le téléphone n'envoie qu'un identifiant. Les actions sensibles demandent une confirmation.
 */
@Composable
fun CommandPanel(client: HubClient, actions: List<ActionItem>, lastResult: String, modifier: Modifier = Modifier) {
    val pages = actions.map { it.page }.distinct()
    var page by remember { mutableStateOf("") }
    val current = if (page in pages) page else pages.firstOrNull() ?: ""
    var pending by remember { mutableStateOf<ActionItem?>(null) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.isEmpty()) {
            Text("En attente de la liste d'actions du PC…", color = Color.LightGray)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pages.forEach { p ->
                Button(
                    onClick = { page = p },
                    colors = ButtonDefaults.buttonColors(containerColor = if (p == current) Accent else Idle),
                ) { Text(p) }
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(actions.filter { it.page == current }, key = { it.id }) { a ->
                Button(
                    onClick = { if (a.sensitive) pending = a else client.runAction(a.id) },
                    modifier = Modifier.fillMaxWidth().height(76.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (a.sensitive) Warn else Idle),
                ) { Text(a.label, textAlign = TextAlign.Center) }
            }
        }
        if (lastResult.isNotEmpty()) Text(lastResult, color = Color.LightGray)
    }

    pending?.let { a ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Confirmer l'action") },
            text = { Text("Exécuter « ${a.label} » sur le PC ?") },
            confirmButton = { TextButton(onClick = { client.runAction(a.id, confirmed = true); pending = null }) { Text("Exécuter") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Annuler") } },
        )
    }
}

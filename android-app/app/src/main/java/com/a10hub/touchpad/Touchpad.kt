package com.a10hub.touchpad

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.a10hub.networking.HubClient
import kotlin.math.abs

private const val TAP_MAX_MS = 220L
private const val TAP_SLOP_PX = 18f
private const val FLUSH_MS = 16L // ~60 Hz : fusion des événements pour ne pas saturer le Wi-Fi

/**
 * Gestes :
 *  - 1 doigt : déplacement relatif ; tap = clic gauche
 *  - 2 doigts : défilement vertical ; tap à 2 doigts = clic droit
 */
@Composable
fun Touchpad(client: HubClient, settings: TouchpadSettings, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val s = rememberUpdatedState(settings)
    val acc = PointerAccumulator()

    androidx.compose.foundation.layout.Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xFF111A2E))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val t0 = System.currentTimeMillis()
                    var maxPointers = 1
                    var travelled = 0f
                    var pendX = 0f; var pendY = 0f; var pendScroll = 0f
                    var lastFlush = t0
                    acc.reset()

                    while (true) {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size > maxPointers) maxPointers = pressed.size

                        if (ev.type == PointerEventType.Move && pressed.isNotEmpty()) {
                            // Déplacement moyen des doigts, coordonnées relatives
                            var mx = 0f; var my = 0f
                            pressed.forEach { mx += it.position.x - it.previousPosition.x; my += it.position.y - it.previousPosition.y }
                            mx /= pressed.size; my /= pressed.size
                            travelled += abs(mx) + abs(my)
                            if (pressed.size >= 2) pendScroll += my else { pendX += mx; pendY += my }
                            ev.changes.forEach { it.consume() }

                            val now = System.currentTimeMillis()
                            if (now - lastFlush >= FLUSH_MS) {
                                lastFlush = now
                                val sign = if (s.value.leftHanded) -1f else 1f
                                if (pendX != 0f || pendY != 0f) {
                                    val (ox, oy) = acc.apply(pendX * sign, pendY, s.value)
                                    client.move(ox, oy); pendX = 0f; pendY = 0f
                                }
                                if (pendScroll != 0f) { client.scroll(scrollUnits(pendScroll, s.value)); pendScroll = 0f }
                            }
                        }

                        if (ev.changes.all { it.changedToUp() || !it.pressed }) {
                            val dur = System.currentTimeMillis() - t0
                            if (dur <= TAP_MAX_MS && travelled < TAP_SLOP_PX) {
                                val btn = if (maxPointers >= 2) "right" else "left"
                                client.button(btn, "click")
                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            } else {
                                if (pendX != 0f || pendY != 0f) { val (ox, oy) = acc.apply(pendX, pendY, s.value); client.move(ox, oy) }
                                if (pendScroll != 0f) client.scroll(scrollUnits(pendScroll, s.value))
                            }
                            break
                        }
                    }
                }
            }
    )
}

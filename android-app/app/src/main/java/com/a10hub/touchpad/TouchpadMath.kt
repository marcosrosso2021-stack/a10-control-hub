package com.a10hub.touchpad

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Paramètres réglables du touchpad. */
data class TouchpadSettings(
    val sensitivity: Float = 1.6f,   // multiplicateur de base
    val acceleration: Float = 0.35f, // 0 = linéaire ; plus grand = plus d'accélération
    val invertScroll: Boolean = false,
    val leftHanded: Boolean = false,
)

/** Convertit un déplacement en pixels en déplacement curseur (accélération douce, avec reste fractionnaire). */
class PointerAccumulator {
    private var remX = 0f
    private var remY = 0f

    fun apply(dxPx: Float, dyPx: Float, s: TouchpadSettings): Pair<Int, Int> {
        val speed = (abs(dxPx) + abs(dyPx))
        val gain = s.sensitivity * (1f + s.acceleration * (speed / 8f).coerceAtMost(6f).pow(1.2f) / 3f)
        remX += dxPx * gain
        remY += dyPx * gain
        val ox = remX.roundToInt(); val oy = remY.roundToInt()
        remX -= ox; remY -= oy
        return ox to oy
    }

    fun reset() { remX = 0f; remY = 0f }
}

/** Défilement : 120 = un cran de molette Windows. Le contenu suit le doigt (défilement « naturel ») sauf inversion. */
fun scrollUnits(dyPx: Float, s: TouchpadSettings): Int {
    val units = (dyPx * 4f).roundToInt()
    return if (s.invertScroll) -units else units
}

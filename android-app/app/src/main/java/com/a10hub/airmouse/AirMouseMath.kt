package com.a10hub.airmouse

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

data class AirMouseSettings(
    val sensitivity: Float = 1.0f,
    val deadZone: Float = 0.05f,   // en « g » (≈ sinus de l'angle) : 0,05 ≈ 3°
    val invertH: Boolean = false,
    val invertV: Boolean = false,
)

/**
 * Souris par inclinaison à partir du SEUL accéléromètre (le Galaxy A10 n'a pas de gyroscope garanti).
 *
 * Principe : au repos, l'accéléromètre mesure la gravité. On mémorise la gravité dans la position
 * « neutre » (calibration), puis l'écart par rapport à cette référence donne l'inclinaison.
 * L'inclinaison pilote la VITESSE du curseur (comme un joystick), pas sa position.
 * Le curseur va vers le bord abaissé du téléphone.
 *
 * Ce n'est pas un suivi spatial précis : les mouvements d'accélération (marcher, secouer)
 * perturbent la mesure. Protections : filtre passe-bas, zone morte, vitesse plafonnée,
 * et rejet des échantillons dont la norme s'écarte trop de 1 g (secousse, chute).
 */
class AirMouseMath {
    companion object {
        const val G = 9.80665f
        const val CAL_SAMPLES = 25
        const val MAX_TILT = 0.5f       // ≈ 30° : au-delà, vitesse maximale
        const val MAX_SPEED_PX = 18f    // pixels par échantillon (~50 Hz) à sensibilité 1
        const val SHAKE_LIMIT = 0.35f   // écart toléré de la norme à 1 g
        private const val ALPHA = 0.2f  // filtre passe-bas : plus petit = plus lisse mais plus lent
    }

    private var fx = 0f
    private var fy = 0f
    private var fz = 0f
    private var hasFilter = false
    private var ref: FloatArray? = null
    private val calSum = FloatArray(3)
    private var calCount = 0
    private var verticalIsZ = false
    private var remX = 0f
    private var remY = 0f

    var calibrating = false
        private set
    val isCalibrated: Boolean get() = ref != null

    /** Remet à zéro le filtre et les restes (reprise après pause) ; la calibration est conservée. */
    fun reset() { hasFilter = false; remX = 0f; remY = 0f }

    /** Lance la calibration : le téléphone doit rester immobile dans la position neutre (~0,5 s). */
    fun startCalibration() {
        calSum.fill(0f); calCount = 0; calibrating = true; ref = null; remX = 0f; remY = 0f
    }

    /** [ax],[ay],[az] en m/s² ; retourne le déplacement du curseur en pixels pour cet échantillon. */
    fun update(ax: Float, ay: Float, az: Float, s: AirMouseSettings): Pair<Int, Int> {
        val norm = sqrt(ax * ax + ay * ay + az * az) / G
        if (abs(norm - 1f) > SHAKE_LIMIT) return 0 to 0      // secousse ou chute : ignoré

        if (!hasFilter) { fx = ax; fy = ay; fz = az; hasFilter = true }
        else { fx += ALPHA * (ax - fx); fy += ALPHA * (ay - fy); fz += ALPHA * (az - fz) }
        val gx = fx / G
        val gy = fy / G
        val gz = fz / G

        if (calibrating) {
            calSum[0] += gx; calSum[1] += gy; calSum[2] += gz; calCount++
            if (calCount >= CAL_SAMPLES) {
                val r = floatArrayOf(calSum[0] / calCount, calSum[1] / calCount, calSum[2] / calCount)
                ref = r
                // Téléphone à plat (gravité surtout sur z) : l'axe « avant/arrière » est y.
                // Téléphone debout (gravité surtout sur y) : l'axe « avant/arrière » est z.
                verticalIsZ = abs(r[1]) > abs(r[2])
                calibrating = false
            }
            return 0 to 0
        }
        val r = ref ?: return 0 to 0

        // Convention Android : bord droit abaissé => ax diminue ; bord haut abaissé (à plat) => ay diminue ;
        // haut incliné vers l'avant (debout) => az augmente.
        val tiltX = gx - r[0]
        val tiltV = if (verticalIsZ) gz - r[2] else gy - r[1]
        var h = -tiltX                              // vers le bord abaissé
        var v = if (verticalIsZ) -tiltV else tiltV  // écran : y vers le bas, donc haut abaissé => négatif
        if (s.invertH) h = -h
        if (s.invertV) v = -v

        remX += curve(h, s.deadZone) * MAX_SPEED_PX * s.sensitivity
        remY += curve(v, s.deadZone) * MAX_SPEED_PX * s.sensitivity
        val ox = remX.toInt()
        val oy = remY.toInt()
        remX -= ox; remY -= oy
        return ox to oy
    }

    /** Zone morte puis courbe douce (puissance 1,6) jusqu'à MAX_TILT. */
    private fun curve(t: Float, dead: Float): Float {
        val a = abs(t)
        if (a <= dead) return 0f
        val e = ((minOf(a, MAX_TILT) - dead) / (MAX_TILT - dead)).coerceIn(0f, 1f)
        val v = e.pow(1.6f)
        return if (t < 0) -v else v
    }
}

package app.diaporama

import android.graphics.Matrix
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Réglage propre à une photo, appliqué par-dessus le cadrage général.
 * [scale] multiplie la taille donnée par le cadrage, [rotation] est en degrés (sens horaire),
 * [dx] et [dy] décalent le centre en fraction de la largeur et de la hauteur de l'écran :
 * le même réglage donne le même rendu dans l'aperçu et sur l'écran.
 */
data class PhotoTransform(val scale: Float = 1f, val rotation: Float = 0f, val dx: Float = 0f, val dy: Float = 0f) {

    val isIdentity: Boolean get() = this == PhotoTransform()

    /** Nombre de quarts de tour le plus proche : au-delà de 45°, le cadrage se fait sur la photo couchée. */
    val quarterOdd: Boolean get() = Math.floorMod((rotation / 90f).roundToInt(), 2) == 1

    /**
     * Échelle du cadrage général, avant [scale]. En Remplir, la photo inclinée grossit juste assez
     * pour couvrir encore tout l'écran, sans coin vide.
     */
    fun baseScale(bw: Float, bh: Float, w: Float, h: Float, mode: FillMode): Float {
        val iw = if (quarterOdd) bh else bw
        val ih = if (quarterOdd) bw else bh
        val a = Math.toRadians(tilt.toDouble())
        val c = abs(cos(a)).toFloat(); val s = abs(sin(a)).toFloat()
        return when (mode) {
            FillMode.FILL -> max((w * c + h * s) / iw, (w * s + h * c) / ih)
            FillMode.FIT -> min(w / iw, h / ih)
            FillMode.CENTER -> min(1f, min(w / iw, h / ih))
        }
    }

    /** Matrice qui place une image de [bw]×[bh] dans une surface de [w]×[h]. */
    fun matrix(bw: Float, bh: Float, w: Float, h: Float, mode: FillMode): Matrix = Matrix().apply {
        val s = baseScale(bw, bh, w, h, mode) * scale
        setTranslate(-bw / 2, -bh / 2)
        postScale(s, s)
        postRotate(rotation)
        postTranslate(w / 2 + dx * w, h / 2 + dy * h)
    }

    /**
     * Applique un geste à deux doigts autour du point [cx], [cy] (pixels de la surface).
     * Si la rotation passe d'un quart de tour à l'autre, l'échelle est compensée pour que la photo ne saute pas.
     */
    fun gesture(
        cx: Float, cy: Float, panX: Float, panY: Float, zoom: Float, turn: Float,
        bw: Float, bh: Float, w: Float, h: Float, mode: FillMode,
    ): PhotoTransform {
        val a = Math.toRadians(turn.toDouble())
        val vx = w / 2 + dx * w - cx
        val vy = h / 2 + dy * h - cy
        val nx = cx + ((vx * cos(a) - vy * sin(a)) * zoom).toFloat() + panX
        val ny = cy + ((vx * sin(a) + vy * cos(a)) * zoom).toFloat() + panY
        val turned = copy(rotation = normalize(rotation + turn))
        val keep = baseScale(bw, bh, w, h, mode) / turned.baseScale(bw, bh, w, h, mode)
        return turned.copy(
            scale = (scale * zoom * keep).coerceIn(MIN_SCALE, MAX_SCALE),
            dx = (nx - w / 2) / w,
            dy = (ny - h / 2) / h,
        )
    }

    /** Quarts de tour entiers, et l'inclinaison fine par-dessus, entre -45° et 45°. */
    val quarter: Int get() = (rotation / 90f).roundToInt()
    val tilt: Float get() = rotation - quarter * 90f

    /** Juste sous 45° : à 45° pile, l'arrondi passerait au quart de tour suivant et la photo basculerait. */
    fun withTilt(deg: Float) = copy(rotation = normalize(quarter * 90f + deg.coerceIn(-MAX_TILT, MAX_TILT)))

    /** Quart de tour : la photo est recadrée dans son nouveau sens. */
    fun rotateQuarter(clockwise: Boolean) = copy(rotation = normalize(rotation + if (clockwise) 90f else -90f))

    fun encode() = "$scale;$rotation;$dx;$dy"

    companion object {
        const val MIN_SCALE = 0.2f
        const val MAX_SCALE = 10f
        const val MAX_TILT = 44.9f

        fun decode(s: String): PhotoTransform? = runCatching {
            val (scale, rotation, dx, dy) = s.split(';').map { it.toFloat() }
            PhotoTransform(scale, rotation, dx, dy)
        }.getOrNull()

        /** Angle ramené dans ]-180, 180]. */
        private fun normalize(deg: Float): Float {
            val d = ((deg % 360f) + 360f) % 360f
            return if (d > 180f) d - 360f else d
        }
    }
}

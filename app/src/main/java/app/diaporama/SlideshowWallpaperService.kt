package app.diaporama

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import android.app.WallpaperManager
import androidx.core.content.edit
import java.util.Calendar
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class SlideshowWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = SlideshowEngine()

    inner class SlideshowEngine : Engine(), SharedPreferences.OnSharedPreferenceChangeListener, SensorEventListener {
        private val settings = Settings(this@SlideshowWallpaperService)
        private val main = Handler(Looper.getMainLooper())
        private val loaderThread = HandlerThread("diaporama-loader").apply { start() }
        private val loader = Handler(loaderThread.looper)
        private val sensors = getSystemService(SensorManager::class.java)

        private var photos: List<Uri> = emptyList()
        private var order: List<Int> = emptyList()
        private var pos = 0

        private var current: Bitmap? = null
        private var previous: Bitmap? = null
        /** URI des photos affichées, pour retrouver leur réglage propre. */
        private var currentUri: String? = null
        private var previousUri: String? = null
        private var fadeStart = 0L
        private var width = 0
        private var height = 0
        private var visible = false

        private var lastTap = 0L
        private var lastShake = 0L

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tick = Runnable { onTick() }
        private val frame = Runnable { draw() }
        /** Redécodage après une retouche, une fois les gestes finis : un zoom peut demander plus de définition. */
        private val redecode = Runnable { loadCurrent(animate = false) }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                next()
            }
        }

        /** Change à la mise en veille, sans fondu : la nouvelle photo est prête au réveil. */
        private val screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (settings.screenOff) next(animate = false)
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            settings.prefs.registerOnSharedPreferenceChangeListener(this)
            registerReceiver(receiver, IntentFilter(Settings.ACTION_NEXT), RECEIVER_NOT_EXPORTED)
            // Diffusion système : le receveur doit être exporté pour la recevoir
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_EXPORTED)
            reloadPhotos()
        }

        override fun onDestroy() {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
            unregisterReceiver(receiver)
            unregisterReceiver(screenOffReceiver)
            sensors.unregisterListener(this)
            main.removeCallbacksAndMessages(null)
            loaderThread.quitSafely()
            super.onDestroy()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            super.onSurfaceChanged(holder, format, w, h)
            val resized = w != width || h != height
            width = w; height = h
            if (resized) loadCurrent(animate = false) else draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                draw()
                updateShakeListener()
                onTick()
            } else {
                sensors.unregisterListener(this)
                main.removeCallbacks(tick)
            }
        }

        override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
            when (key) {
                K_CURRENT, K_LAST_CHANGE -> Unit
                Settings.K_TRANSFORM + currentUri -> {
                    draw()
                    main.removeCallbacks(redecode)
                    main.postDelayed(redecode, 500)
                }
                Settings.K_FOLDERS, Settings.K_ALBUMS, Settings.K_PHOTOS, Settings.K_SHUFFLE -> reloadPhotos()
                Settings.K_FILL -> draw()
                Settings.K_SHAKE -> updateShakeListener()
                else -> if (key?.startsWith(Settings.K_TRANSFORM) == true || key?.startsWith(Settings.K_UPDATES) == true) Unit
                    else if (visible) { main.removeCallbacks(tick); scheduleTick() }
            }
        }

        // ---- Déclencheurs ----

        /** Double-tap sur le bureau (le lanceur transmet les taps au fond d'écran). */
        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: Bundle?, resultRequested: Boolean): Bundle? {
            if (action == WallpaperManager.COMMAND_TAP && settings.doubleTap) {
                val now = SystemClock.uptimeMillis()
                if (now - lastTap < 400) { lastTap = 0; next() } else lastTap = now
            }
            return null
        }

        override fun onSensorChanged(e: SensorEvent) {
            val g = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) /
                SensorManager.GRAVITY_EARTH
            val now = SystemClock.uptimeMillis()
            if (g > 2.5f && now - lastShake > 1200) { lastShake = now; next() }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun updateShakeListener() {
            sensors.unregisterListener(this)
            if (visible && settings.shake) {
                sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                    sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }

        /** Intervalle et heures fixes : on vérifie à chaque retour à l'écran puis on programme l'échéance suivante. */
        private fun onTick() {
            if (!visible) return
            if (nextDueAt() <= System.currentTimeMillis()) next() else scheduleTick()
        }

        private fun scheduleTick() {
            main.removeCallbacks(tick)
            val due = nextDueAt()
            if (due == Long.MAX_VALUE) return
            main.postDelayed(tick, max(1000L, due - System.currentTimeMillis()))
        }

        private fun nextDueAt(): Long {
            val last = settings.prefs.getLong(K_LAST_CHANGE, 0L)
            var due = Long.MAX_VALUE
            if (settings.intervalEnabled) due = last + settings.intervalMinutes * 60_000L
            if (settings.hoursEnabled && settings.hours.isNotEmpty()) due = min(due, nextHourAfter(last))
            return due
        }

        private fun nextHourAfter(time: Long): Long {
            val cal = Calendar.getInstance().apply {
                timeInMillis = time
                set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            repeat(48) {
                cal.add(Calendar.HOUR_OF_DAY, 1)
                if (cal.get(Calendar.HOUR_OF_DAY) in settings.hours) return cal.timeInMillis
            }
            return Long.MAX_VALUE
        }

        // ---- Photos ----

        private fun reloadPhotos() {
            loader.post {
                val list = PhotoSource.collect(this@SlideshowWallpaperService, settings)
                val currentUri = settings.prefs.getString(K_CURRENT, null)
                main.post {
                    photos = list
                    buildOrder()
                    val i = photos.indexOfFirst { it.toString() == currentUri }
                    pos = if (i >= 0) order.indexOf(i).coerceAtLeast(0) else 0
                    loadCurrent(animate = false)
                    if (visible) scheduleTick()
                }
            }
        }

        private fun buildOrder() {
            order = photos.indices.toList().let { if (settings.shuffle) it.shuffled() else it }
        }

        fun next(animate: Boolean = settings.transition) {
            main.post {
                if (photos.isEmpty()) return@post
                pos++
                if (pos >= order.size) { buildOrder(); pos = 0 }
                settings.prefs.edit { putLong(K_LAST_CHANGE, System.currentTimeMillis()) }
                loadCurrent(animate)
                if (visible) scheduleTick()
            }
        }

        private fun loadCurrent(animate: Boolean) {
            if (photos.isEmpty() || width == 0) { current = null; currentUri = null; draw(); return }
            val uri = photos[order[pos]]
            val w = width; val h = height
            loader.post {
                val bmp = runCatching { decode(uri, w, h, settings.transform(uri.toString())) }.getOrNull()
                main.post {
                    if (bmp == null) {
                        // Photo illisible (supprimée…) : on passe à la suivante
                        if (photos.size > 1) { pos = (pos + 1) % order.size; loadCurrent(animate) }
                        return@post
                    }
                    settings.prefs.edit { putString(K_CURRENT, uri.toString()) }
                    previous = if (animate) current else null
                    previousUri = if (animate) currentUri else null
                    current = bmp
                    currentUri = uri.toString()
                    fadeStart = SystemClock.uptimeMillis()
                    draw()
                }
            }
        }

        /** Décode juste assez grand pour l'écran, en tenant compte du zoom propre à la photo. */
        private fun decode(uri: Uri, w: Int, h: Int, t: PhotoTransform): Bitmap {
            val src = ImageDecoder.createSource(contentResolver, uri)
            val zoom = t.scale.coerceIn(1f, 4f)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val iw = info.size.width; val ih = info.size.height
                // Quart de tour : la largeur de la photo couvre la hauteur de l'écran
                val (sw, sh) = if (t.quarterOdd) h to w else w to h
                var s = max(1, (min(iw / sw.toFloat(), ih / sh.toFloat()) / zoom).toInt())
                // Plafond mémoire : un dessin trop grand ferait planter le canevas
                while (iw.toLong() / s * (ih / s) > MAX_PIXELS) s++
                if (s > 1) dec.setTargetSampleSize(s)
            }
        }

        // ---- Dessin ----

        private fun draw() {
            main.removeCallbacks(frame)
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                val t = ((SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                val prev = previous
                if (prev != null && t < 1f) {
                    paint.alpha = 255
                    drawBitmap(canvas, prev, previousUri)
                    paint.alpha = (t * 255).toInt()
                } else {
                    previous = null
                    paint.alpha = 255
                }
                current?.let { drawBitmap(canvas, it, currentUri) }
                if (previous != null) main.postDelayed(frame, 16)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawBitmap(canvas: Canvas, bmp: Bitmap, uri: String?) {
            val t = uri?.let(settings::transform) ?: PhotoTransform()
            val m = t.matrix(bmp.width.toFloat(), bmp.height.toFloat(), width.toFloat(), height.toFloat(), settings.fillMode)
            canvas.drawBitmap(bmp, m, paint)
        }
    }

    companion object {
        private const val FADE_MS = 700L
        private const val MAX_PIXELS = 16_000_000L
        const val K_CURRENT = "current"
        const val K_LAST_CHANGE = "last_change"
    }
}

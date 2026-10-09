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
import android.graphics.RectF
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
        private var fadeStart = 0L
        private var width = 0
        private var height = 0
        private var visible = false

        private var lastTap = 0L
        private var lastShake = 0L

        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val tick = Runnable { onTick() }
        private val frame = Runnable { draw() }

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT -> if (settings.unlock) next()
                    Settings.ACTION_NEXT -> next()
                }
            }
        }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            settings.prefs.registerOnSharedPreferenceChangeListener(this)
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Settings.ACTION_NEXT)
            }
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            reloadPhotos()
        }

        override fun onDestroy() {
            settings.prefs.unregisterOnSharedPreferenceChangeListener(this)
            unregisterReceiver(receiver)
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
                Settings.K_FOLDERS, Settings.K_ALBUMS, Settings.K_SHUFFLE -> reloadPhotos()
                Settings.K_FILL -> draw()
                Settings.K_SHAKE -> updateShakeListener()
                else -> if (visible) { main.removeCallbacks(tick); scheduleTick() }
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

        fun next() {
            main.post {
                if (photos.isEmpty()) return@post
                pos++
                if (pos >= order.size) { buildOrder(); pos = 0 }
                settings.prefs.edit { putLong(K_LAST_CHANGE, System.currentTimeMillis()) }
                loadCurrent(animate = settings.transition)
                if (visible) scheduleTick()
            }
        }

        private fun loadCurrent(animate: Boolean) {
            if (photos.isEmpty() || width == 0) { current = null; draw(); return }
            val uri = photos[order[pos]]
            val w = width; val h = height
            loader.post {
                val bmp = runCatching { decode(uri, w, h) }.getOrNull()
                main.post {
                    if (bmp == null) {
                        // Photo illisible (supprimée…) : on passe à la suivante
                        if (photos.size > 1) { pos = (pos + 1) % order.size; loadCurrent(animate) }
                        return@post
                    }
                    settings.prefs.edit { putString(K_CURRENT, uri.toString()) }
                    previous = if (animate) current else null
                    current = bmp
                    fadeStart = SystemClock.uptimeMillis()
                    draw()
                }
            }
        }

        private fun decode(uri: Uri, w: Int, h: Int): Bitmap {
            val src = ImageDecoder.createSource(contentResolver, uri)
            return ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val s = min(info.size.width / w, info.size.height / h)
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
                    drawBitmap(canvas, prev)
                    paint.alpha = (t * 255).toInt()
                } else {
                    previous = null
                    paint.alpha = 255
                }
                current?.let { drawBitmap(canvas, it) }
                if (previous != null) main.postDelayed(frame, 16)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun drawBitmap(canvas: Canvas, bmp: Bitmap) {
            val bw = bmp.width.toFloat(); val bh = bmp.height.toFloat()
            val scale = when (settings.fillMode) {
                FillMode.FILL -> max(width / bw, height / bh)
                FillMode.FIT -> min(width / bw, height / bh)
                FillMode.CENTER -> min(1f, min(width / bw, height / bh))
            }
            val dw = bw * scale; val dh = bh * scale
            val left = (width - dw) / 2; val top = (height - dh) / 2
            canvas.drawBitmap(bmp, null, RectF(left, top, left + dw, top + dh), paint)
        }
    }

    companion object {
        private const val FADE_MS = 700L
        const val K_CURRENT = "current"
        const val K_LAST_CHANGE = "last_change"
    }
}

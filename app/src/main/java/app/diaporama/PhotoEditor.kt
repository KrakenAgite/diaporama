package app.diaporama

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Retouche photo par photo : pincer pour la taille, tourner à deux doigts, glisser pour déplacer.
 * L'aperçu a les proportions de l'écran et utilise la même matrice que le fond d'écran.
 */
@Composable
fun PhotoEditor(s: Settings, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val photos by produceState<List<Uri>?>(null) {
        value = withContext(Dispatchers.IO) { PhotoSource.collect(ctx, s) }
    }
    var index by remember { mutableIntStateOf(-1) }
    LaunchedEffect(photos) {
        val list = photos ?: return@LaunchedEffect
        val shown = s.prefs.getString(SlideshowWallpaperService.K_CURRENT, null)
        index = list.indexOfFirst { it.toString() == shown }.coerceAtLeast(0)
    }
    val screen = remember {
        ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.let { it.width() / it.height().toFloat() }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).systemBarsPadding().padding(16.dp)) {
        val list = photos
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour", "Back"))
            }
            Text(
                tr("Ajuster les photos", "Adjust photos"),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 4.dp).weight(1f),
            )
            if (list != null && index >= 0) Text("${index + 1} / ${list.size}", style = MaterialTheme.typography.bodyMedium)
        }
        when {
            list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            list.isEmpty() -> Text(tr("Aucune photo choisie", "No photos chosen"), modifier = Modifier.weight(1f))
            index >= 0 -> {
                val uri = list[index].toString()
                key(uri) {
                    EditPanel(
                        s, uri, screen,
                        onPrevious = { index = (index - 1 + list.size) % list.size },
                        onNext = { index = (index + 1) % list.size },
                    )
                }
            }
        }
    }
}

/**
 * Aperçu aux proportions de l'écran, molette d'inclinaison et boutons, à la façon de Photos sur iPhone.
 * Le réglage est enregistré peu après la fin des gestes.
 */
@Composable
private fun ColumnScope.EditPanel(s: Settings, uri: String, screen: Float, onPrevious: () -> Unit, onNext: () -> Unit) {
    val ctx = LocalContext.current
    val bmp by produceState<Bitmap?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { decodePreview(ctx, Uri.parse(uri)) }.getOrNull() }
    }
    var t by remember { mutableStateOf(s.transform(uri)) }
    DisposableEffect(uri) { onDispose { s.setTransform(uri, t) } }
    LaunchedEffect(t) {
        delay(300)
        s.setTransform(uri, t)
    }
    val mode = s.fillMode
    var dialing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    val gridAlpha by animateFloatAsState(if (dialing || moving) 1f else 0f, label = "grille")
    val paint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }

    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
    Box(
        Modifier.aspectRatio(screen, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black)
            // Double-tap : la photo revient telle qu'importée
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { t = PhotoTransform() }) }
            // Fin du déplacement : quand tous les doigts sont levés
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { it.pressed })
                    moving = false
                }
            }
            .pointerInput(bmp) {
                val b = bmp ?: return@pointerInput
                // Pas de rotation au pincement : elle se règle avec la molette
                detectTransformGestures { centroid, pan, zoom, _ ->
                    moving = true
                    t = t.gesture(
                        centroid.x, centroid.y, pan.x, pan.y, zoom, 0f,
                        b.width.toFloat(), b.height.toFloat(), size.width.toFloat(), size.height.toFloat(), mode,
                    )
                }
            }
    ) {
        val b = bmp
        if (b == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val m = t.matrix(b.width.toFloat(), b.height.toFloat(), size.width, size.height, mode)
                drawIntoCanvas { it.nativeCanvas.drawBitmap(b, m, paint) }
                // Grille des tiers pendant qu'on tourne la molette ou qu'on déplace la photo
                if (gridAlpha > 0f) {
                    val c = Color.White.copy(alpha = 0.7f * gridAlpha)
                    for (i in 1..2) {
                        val x = size.width * i / 3; val y = size.height * i / 3
                        drawLine(c, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                        drawLine(c, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                }
            }
        }
    }
    }

    Text(
        "${t.tilt.roundToInt()}°",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp),
    )
    TiltDial(t.tilt, onActive = { dialing = it }) { t = t.withTilt(it) }
    Text(
        tr(
            "Pince pour agrandir, glisse pour déplacer, tourne avec la molette. Touche deux fois pour revenir à l'original.",
            "Pinch to resize, drag to move, turn the dial to rotate. Double-tap to go back to the original.",
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = tr("Photo précédente", "Previous photo"))
        }
        // Grisé tant que la photo est telle qu'importée
        IconButton(onClick = { t = PhotoTransform() }, enabled = !t.isIdentity) {
            Icon(Icons.AutoMirrored.Outlined.Undo, contentDescription = tr("Revenir à l'original", "Back to original"))
        }
        IconButton(onClick = { t = t.rotateQuarter(clockwise = true) }) {
            Icon(Icons.Outlined.RotateRight, contentDescription = tr("Tourner de 90°", "Rotate 90°"))
        }
        IconButton(onClick = onNext) {
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = tr("Photo suivante", "Next photo"))
        }
    }
}

/**
 * Molette en arc de cercle : un point par degré, un trait tous les 5°, plus long tous les 15°, le zéro en couleur.
 * Le zéro est légèrement aimanté, avec un petit retour haptique.
 * Le repère fixe au centre indique l'inclinaison ; on fait glisser l'arc pour la changer.
 */
@Composable
private fun TiltDial(tilt: Float, onActive: (Boolean) -> Unit, onTilt: (Float) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val current by rememberUpdatedState(tilt)
    val haptic = LocalHapticFeedback.current
    Canvas(
        Modifier.fillMaxWidth().height(56.dp).clipToBounds().pointerInput(Unit) {
            // Un degré d'inclinaison = DIAL_STEP degrés d'arc
            val pxPerDeg = size.width * DIAL_RADIUS * Math.toRadians(DIAL_STEP.toDouble()).toFloat()
            // Angle suivi sans aimantation, pour pouvoir ressortir de la zone du zéro
            var raw = 0f
            detectHorizontalDragGestures(
                onDragStart = { raw = current; onActive(true) },
                onDragEnd = { onActive(false) },
                onDragCancel = { onActive(false) },
            ) { change, dx ->
                change.consume()
                raw = (raw - dx / pxPerDeg).coerceIn(-PhotoTransform.MAX_TILT, PhotoTransform.MAX_TILT)
                val snapped = if (kotlin.math.abs(raw) < DIAL_SNAP) 0f else raw
                if (snapped == 0f && current != 0f) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onTilt(snapped)
            }
        }
    ) {
        val r = size.width * DIAL_RADIUS
        val cx = size.width / 2
        val cy = 30.dp.toPx() + r
        for (deg in -45..45) {
            val a = Math.toRadians(((deg - tilt) * DIAL_STEP).toDouble())
            if (kotlin.math.abs(a) > Math.toRadians(DIAL_SPAN)) continue
            val ux = sin(a).toFloat(); val uy = -cos(a).toFloat()
            val base = Offset(cx + r * ux, cy + r * uy)
            if (deg % 5 != 0) {
                drawCircle(colors.onSurfaceVariant.copy(alpha = 0.5f), 1.dp.toPx(), base)
                continue
            }
            // Trait dans l'axe du rayon, vers le haut depuis l'arc
            val (color, len) = when {
                deg == 0 -> colors.primary to 14.dp.toPx()
                deg % 15 == 0 -> colors.onSurface to 10.dp.toPx()
                else -> colors.onSurfaceVariant.copy(alpha = 0.6f) to 6.dp.toPx()
            }
            drawLine(color, base, base + Offset(ux * len, uy * len), 2.dp.toPx(), StrokeCap.Round)
        }
        // Repère fixe, dans le prolongement du trait central
        drawLine(colors.onSurface, Offset(cx, 2.dp.toPx()), Offset(cx, 12.dp.toPx()), 3.dp.toPx(), StrokeCap.Round)
    }
}

private const val DIAL_RADIUS = 2.5f
private const val DIAL_STEP = 0.25f
private const val DIAL_SPAN = 12.0
/** Zone d'aimantation autour de 0°, en degrés. */
private const val DIAL_SNAP = 1.5f

private fun decodePreview(ctx: android.content.Context, uri: Uri): Bitmap =
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
        dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val s = min(info.size.width, info.size.height) / PREVIEW_PX
        if (s > 1) dec.setTargetSampleSize(s)
    }

private const val PREVIEW_PX = 1200

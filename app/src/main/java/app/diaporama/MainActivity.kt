package app.diaporama

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Relu à chaque retour dans l'app (après le sélecteur de fond d'écran par exemple). */
    private var isActive by mutableStateOf(false)
    /** Incrémenté à chaque retour : relit les autorisations (installation, notifications). */
    private var resumes by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) {
                // Pas de Surface : sans cela, textes et icônes seraient noirs par défaut
                CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                    key(resumes) { SettingsScreen(Settings(ctx), isActive) }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isActive = WallpaperManager.getInstance(this).wallpaperInfo?.packageName == packageName
        resumes++
    }

    override fun onStart() {
        super.onStart()
        // À chaque ouverture, si l'option est active
        Updater.scope.launch { Updater(this@MainActivity).check() }
    }
}

/** Sous-menus des réglages, dans l'ordre de la liste. */
enum class SettingsPage(val icon: ImageVector, private val fr: String, private val en: String) {
    PHOTOS(Icons.Outlined.PhotoLibrary, "Photos", "Photos"),
    CHANGE(Icons.Outlined.Autorenew, "Changement", "Changing"),
    DISPLAY(Icons.Outlined.Tune, "Affichage", "Display"),
    ABOUT(Icons.Outlined.Info, "À propos", "About"),
    ;

    val label: String get() = tr(fr, en)
}

private val CARD = RoundedCornerShape(20.dp)
private val INTERVALS = listOf(1, 5, 15, 30, 60, 180, 720, 1440)

private fun intervalLabel(m: Int) = when {
    m < 60 -> "$m min"
    m < 1440 -> "${m / 60} h"
    else -> "${m / 1440} " + tr("j", "d")
}

@Composable
private fun cardBackground(): Color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f)

private fun openWallpaperPicker(ctx: Context) = ctx.startActivity(
    Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
        ComponentName(ctx, SlideshowWallpaperService::class.java)
    )
)

@Composable
fun SettingsScreen(s: Settings, isActive: Boolean) {
    val ctx = LocalContext.current
    var page by remember { mutableStateOf<SettingsPage?>(null) }
    var editing by remember { mutableStateOf(false) }
    val installed = remember { Updater(ctx).installed }
    // Compteur incrémenté à chaque modification pour relire les réglages
    var rev by remember { mutableIntStateOf(0) }
    val change: (() -> Unit) -> Unit = { it(); rev++ }
    BackHandler(enabled = page != null) { page = null }
    BackHandler(enabled = editing) { editing = false }
    // Résultat d'une vérification faite en arrière-plan : on relit l'écran
    DisposableEffect(s) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, k ->
            if (k?.startsWith(Settings.K_UPDATES) == true) rev++
        }
        s.prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { s.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    if (editing) {
        PhotoEditor(s) { editing = false }
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            // Le fond d'écran reste visible derrière un voile
            .background(
                Brush.verticalGradient(
                    0f to MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                    1f to MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                )
            )
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        key(rev) {
            val current = page
            if (current == null) {
                Text("Diaporama", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(vertical = 16.dp))
                if (!isActive) {
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 12.dp).clip(CARD)
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("Diaporama n'est pas ton fond d'écran", "Diaporama isn't your wallpaper"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("Choisis « Écran d'accueil et écran de verrouillage »", "Choose “Home and lock screens”"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Button(onClick = { openWallpaperPicker(ctx) }) { Text(tr("Définir", "Set")) }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsPage.entries.forEach { p ->
                        MenuRow(p.icon, p.label, summary(p, s, installed)) { page = p }
                    }
                    Spacer(Modifier.height(4.dp))
                    MenuRow(Icons.Outlined.SkipNext, tr("Photo suivante", "Next photo"), null, chevron = false) { Settings.requestNext(ctx) }
                }
            } else {
                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { page = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("Retour", "Back"))
                    }
                    Text(current.label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 4.dp))
                }
                Column(
                    Modifier.fillMaxWidth().clip(CARD).background(cardBackground())
                        .padding(horizontal = 18.dp, vertical = 8.dp)
                ) {
                    when (current) {
                        SettingsPage.PHOTOS -> PhotosPage(s, change)
                        SettingsPage.CHANGE -> ChangePage(s, change)
                        SettingsPage.DISPLAY -> DisplayPage(s, change) { editing = true }
                        SettingsPage.ABOUT -> AboutPage(s, change)
                    }
                }
            }
        }
    }
}

/** L'état actuel de chaque sous-menu, en quelques mots. */
private fun summary(page: SettingsPage, s: Settings, installed: String): String = when (page) {
    SettingsPage.PHOTOS -> listOfNotNull(
        s.photos.size.takeIf { it > 0 }?.let { plural(it, "photo") },
        s.folders.size.takeIf { it > 0 }?.let { plural(it, tr("dossier", "folder")) },
        s.albums.size.takeIf { it > 0 }?.let { plural(it, tr("dossier", "gallery folder")) + tr(" de la galerie", "") },
    ).joinToString(" · ").ifEmpty { tr("Aucune photo choisie", "No photos chosen") }
    SettingsPage.CHANGE -> listOfNotNull(
        if (s.intervalEnabled) tr("Toutes les ", "Every ") + intervalLabel(s.intervalMinutes) else null,
        if (s.hoursEnabled && s.hours.isNotEmpty()) plural(s.hours.size, tr("heure", "time")) else null,
        if (s.screenOff) tr("Mise en veille", "Screen off") else null,
        if (s.doubleTap) "Double-tap" else null,
        if (s.shake) tr("Secousse", "Shake") else null,
    ).joinToString(" · ").ifEmpty { tr("Manuel uniquement", "Manual only") }
    SettingsPage.ABOUT -> s.updatesLatest?.let { tr("$it disponible", "$it available") }
        ?: (tr("Version ", "Version ") + installed)
    SettingsPage.DISPLAY -> listOfNotNull(
        s.fillMode.label,
        if (s.shuffle) tr("Aléatoire", "Shuffle") else tr("Dans l'ordre", "In order"),
        if (s.transition) tr("Fondu", "Fade") else null,
    ).joinToString(" · ")
}

@Composable
private fun PhotosPage(s: Settings, change: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current

    // Sélecteur de photos système, ouvert sur l'onglet Albums (albums Google Photos inclus)
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val clip = res.data?.clipData
        val uris = if (clip != null) (0 until clip.itemCount).map { clip.getItemAt(it).uri }
        else listOfNotNull(res.data?.data)
        uris.forEach {
            runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uris.isNotEmpty()) change { s.photos = s.photos + uris.map { it.toString() } }
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            change { s.folders = s.folders + uri.toString() }
        }
    }
    var hasMedia by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
    }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMedia = it[Manifest.permission.READ_MEDIA_IMAGES] == true
    }
    val albums = remember(hasMedia) { if (hasMedia) PhotoSource.listAlbums(ctx) else emptyList() }

    SectionTitle(tr("Mes albums", "My albums"))
    SettingRow(
        tr("Photos choisies", "Chosen photos"),
        if (s.photos.isEmpty()) tr("Aucune", "None") else plural(s.photos.size, "photo"),
        tr("Choisir", "Choose"),
    ) {
        pickPhotos.launch(
            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                .putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, MediaStore.PICK_IMAGES_TAB_ALBUMS)
        )
    }
    HintText(
        tr(
            "Ouvre un album Google Photos et sélectionne jusqu'à ${MediaStore.getPickImagesMaxLimit()} photos à la fois ; recommence pour en ajouter.",
            "Open a Google Photos album and select up to ${MediaStore.getPickImagesMaxLimit()} photos at a time; repeat to add more.",
        )
    )
    if (s.photos.isNotEmpty()) {
        TextButton(onClick = {
            s.photos.forEach {
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            change { s.photos = emptySet() }
        }) { Text(tr("Tout retirer", "Remove all")) }
    }

    SectionTitle(tr("Dossiers", "Folders"))
    s.folders.forEach { f ->
        SettingRow(folderName(f), tr("Avec les sous-dossiers", "Including subfolders"), tr("Retirer", "Remove"), tonal = false) {
            runCatching {
                ctx.contentResolver.releasePersistableUriPermission(Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            change { s.folders = s.folders - f }
        }
    }
    FilledTonalButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.padding(vertical = 8.dp)) {
        Text(tr("Ajouter un dossier", "Add a folder"))
    }

    SectionTitle(tr("Dossiers de la galerie", "Gallery folders"))
    if (!hasMedia) {
        SettingRow(tr("Accès à la galerie", "Gallery access"), tr("Non autorisé", "Not allowed"), tr("Autoriser", "Allow")) {
            askMedia.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }
    } else if (albums.isEmpty()) {
        HintText(tr("Aucun dossier trouvé", "No folders found"))
    }
    albums.forEach { a ->
        SwitchRow(a.name, plural(a.count, "photo"), a.id in s.albums) { on ->
            change { s.albums = if (on) s.albums + a.id else s.albums - a.id }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChangePage(s: Settings, change: (() -> Unit) -> Unit) {
    SwitchRow(tr("À intervalle régulier", "At regular intervals"), if (s.intervalEnabled) tr("Toutes les ", "Every ") + intervalLabel(s.intervalMinutes) else null, s.intervalEnabled) {
        change { s.intervalEnabled = it }
    }
    if (s.intervalEnabled) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            INTERVALS.forEach { m ->
                FilterChip(selected = s.intervalMinutes == m, onClick = { change { s.intervalMinutes = m } }, label = { Text(intervalLabel(m)) })
            }
        }
    }
    SwitchRow(
        tr("À heures fixes", "At set times"),
        if (s.hoursEnabled) s.hours.sorted().joinToString(", ") { hourLabel(it) }.ifEmpty { tr("Aucune heure", "No times") } else null,
        s.hoursEnabled,
    ) { change { s.hoursEnabled = it } }
    if (s.hoursEnabled) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..23).forEach { h ->
                FilterChip(
                    selected = h in s.hours,
                    onClick = { change { s.hours = if (h in s.hours) s.hours - h else s.hours + h } },
                    label = { Text(hourLabel(h)) },
                )
            }
        }
    }
    SwitchRow(tr("À chaque mise en veille", "Every time the screen turns off"), tr("La nouvelle photo est prête au réveil", "The new photo is ready when you wake it"), s.screenOff) { change { s.screenOff = it } }
    SwitchRow(tr("Double-tap sur le bureau", "Double-tap the home screen"), null, s.doubleTap) { change { s.doubleTap = it } }
    SwitchRow(tr("Secouer le téléphone", "Shake the phone"), null, s.shake) { change { s.shake = it } }
    HintText(tr("Ajoute aussi la tuile « Fond suivant » dans les réglages rapides.", "You can also add the “Next wallpaper” tile to Quick Settings."))
}

@Composable
private fun DisplayPage(s: Settings, change: (() -> Unit) -> Unit, onEdit: () -> Unit) {
    SwitchRow(tr("Ordre aléatoire", "Shuffle"), null, s.shuffle) { change { s.shuffle = it } }
    SwitchRow(tr("Transition en fondu", "Fade transition"), null, s.transition) { change { s.transition = it } }
    SectionTitle(tr("Cadrage", "Framing"))
    Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FillMode.entries.forEach { m ->
            FilterChip(selected = s.fillMode == m, onClick = { change { s.fillMode = m } }, label = { Text(m.label) })
        }
    }
    SettingRow(
        tr("Photo par photo", "Photo by photo"),
        tr("Taille, rotation et position de chaque photo", "Size, rotation and position of each photo"),
        tr("Ajuster", "Adjust"),
        onAction = onEdit,
    )
}

@Composable
private fun AboutPage(s: Settings, change: (() -> Unit) -> Unit) {
    val ctx = LocalContext.current
    val updater = remember { Updater(ctx) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var installing by remember { mutableStateOf(false) }
    val canInstall = updater.canInstall()
    val canNotify = updater.canNotify()
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { change {} }
    fun toast(text: String) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
    fun allowInstalls() = ctx.startActivity(updater.unknownSourcesIntent())

    val latest = s.updatesLatest
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(tr("Version", "Version"), style = MaterialTheme.typography.titleMedium)
            Text(
                updater.installed + (latest?.let { " · " + tr("$it disponible", "$it available") } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (latest != null) {
            FilledTonalButton(enabled = !installing, onClick = {
                if (!canInstall) { allowInstalls(); return@FilledTonalButton }
                installing = true
                scope.launch {
                    if (!updater.installNow()) toast(tr("Mise à jour impossible", "Update failed"))
                    installing = false
                }
            }) { Text(if (installing) tr("Installation…", "Installing…") else tr("Installer", "Install")) }
        }
    }
    SwitchRow(
        tr("Vérifier les mises à jour", "Check for updates"),
        s.updatesLastCheck.takeIf { it > 0 }?.let {
            tr("Dernière vérification ", "Last checked ") +
                android.text.format.DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS)
        } ?: tr("Jamais vérifié", "Never checked"),
        s.updatesEnabled,
    ) { on ->
        change { s.updatesEnabled = on }
        if (on && !canNotify) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    SwitchRow(
        tr("Installer automatiquement", "Install automatically"),
        if (canInstall) tr("Dès qu'une version sort", "As soon as a version is out") else tr("Autorisation d'installer requise", "Install permission needed"),
        s.autoInstall,
    ) { on ->
        change { s.autoInstall = on }
        if (on && !canInstall) allowInstalls()
    }
    if (s.autoInstall && !canInstall) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { HintText(tr("Diaporama n'a pas le droit d'installer.", "Diaporama can't install apps yet.")) }
            TextButton(onClick = ::allowInstalls) { Text(tr("Autoriser", "Allow")) }
        }
    }
    FilledTonalButton(
        enabled = !checking,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        onClick = {
            checking = true
            scope.launch {
                val newer = updater.check(force = true)
                checking = false
                change {}
                toast(newer?.let { tr("Diaporama ${it.version} est disponible", "Diaporama ${it.version} is available") } ?: tr("Diaporama est à jour", "Diaporama is up to date"))
            }
        },
    ) { Text(if (checking) tr("Vérification…", "Checking…") else tr("Vérifier maintenant", "Check now")) }
    if (s.updatesEnabled && !canNotify) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { HintText(tr("Notifications bloquées pour Diaporama.", "Notifications are blocked for Diaporama.")) }
            TextButton(onClick = { askNotify.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(tr("Autoriser", "Allow")) }
        }
    }
    SettingRow(tr("Code source", "Source code"), "github.com/KrakenAgite/diaporama", tr("Ouvrir", "Open"), tonal = false) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.RELEASES_PAGE)))
    }
}

private fun hourLabel(h: Int) = tr("${h}h", if (h == 0) "12am" else if (h < 12) "${h}am" else if (h == 12) "12pm" else "${h - 12}pm")

private fun folderName(uri: String) =
    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifEmpty { "/" } }
        .getOrDefault(uri)

@Composable
private fun MenuRow(icon: ImageVector, title: String, summary: String?, chevron: Boolean = true, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(CARD).background(cardBackground()).clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.padding(end = 16.dp).size(24.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun HintText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String, actionLabel: String, tonal: Boolean = true, onAction: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (tonal) FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        else TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

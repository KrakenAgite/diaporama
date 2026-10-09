package app.diaporama

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            val scheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
            MaterialTheme(colorScheme = scheme) { SettingsScreen(Settings(ctx)) }
        }
    }
}

private val INTERVALS = listOf(1, 5, 15, 30, 60, 180, 720, 1440)

private fun intervalLabel(m: Int) = when {
    m < 60 -> "$m min"
    m < 1440 -> "${m / 60} h"
    else -> "${m / 1440} j"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(s: Settings) {
    val ctx = LocalContext.current
    // Compteur incrémenté à chaque modification pour relire les réglages
    var rev by remember { mutableIntStateOf(0) }
    fun change(block: () -> Unit) { block(); rev++ }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            change { s.folders = s.folders + uri.toString() }
        }
    }

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

    var hasMedia by remember {
        mutableStateOf(ctx.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED)
    }
    val askMedia = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasMedia = it[Manifest.permission.READ_MEDIA_IMAGES] == true
    }
    val albums = remember(hasMedia) { if (hasMedia) PhotoSource.listAlbums(ctx) else emptyList() }

    Scaffold(topBar = { TopAppBar(title = { Text("Diaporama") }) }) { pad ->
        key(rev) {
            Column(
                Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        ctx.startActivity(
                            Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                                ComponentName(ctx, SlideshowWallpaperService::class.java)
                            )
                        )
                    }) { Text("Définir le fond d'écran") }
                    OutlinedButton(onClick = { Settings.requestNext(ctx) }) { Text("Suivante") }
                }
                Text(
                    "Dans l'aperçu, choisis « Écran d'accueil et écran de verrouillage » pour l'avoir partout.",
                    style = MaterialTheme.typography.bodySmall
                )

                Section("Dossiers")
                s.folders.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(folderName(f), Modifier.weight(1f))
                        TextButton(onClick = {
                            runCatching {
                                ctx.contentResolver.releasePersistableUriPermission(
                                    Uri.parse(f), Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            change { s.folders = s.folders - f }
                        }) { Text("Retirer") }
                    }
                }
                OutlinedButton(onClick = { pickFolder.launch(null) }) { Text("Ajouter un dossier") }

                Section("Mes albums (Google Photos…)")
                Text(
                    "Ouvre tes albums et sélectionne les photos (jusqu'à ${MediaStore.getPickImagesMaxLimit()} à la fois, " +
                        "tu peux recommencer pour en ajouter).",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        pickPhotos.launch(
                            Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*")
                                .putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit())
                                .putExtra(MediaStore.EXTRA_PICK_IMAGES_LAUNCH_TAB, MediaStore.PICK_IMAGES_TAB_ALBUMS)
                        )
                    }) { Text("Choisir des photos") }
                    if (s.photos.isNotEmpty()) {
                        Text("${s.photos.size} photos", Modifier.weight(1f))
                        TextButton(onClick = {
                            s.photos.forEach {
                                runCatching {
                                    ctx.contentResolver.releasePersistableUriPermission(
                                        Uri.parse(it), Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    )
                                }
                            }
                            change { s.photos = emptySet() }
                        }) { Text("Tout retirer") }
                    }
                }

                Section("Dossiers de la galerie")
                if (!hasMedia) {
                    OutlinedButton(onClick = {
                        askMedia.launch(
                            arrayOf(
                                Manifest.permission.READ_MEDIA_IMAGES,
                                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                            )
                        )
                    }) { Text("Autoriser l'accès à la galerie") }
                } else if (albums.isEmpty()) {
                    Text("Aucun album trouvé")
                }
                albums.forEach { a ->
                    CheckRow("${a.name} (${a.count})", a.id in s.albums) { on ->
                        change { s.albums = if (on) s.albums + a.id else s.albums - a.id }
                    }
                }

                Section("Passer au fond suivant")
                SwitchRow("Toutes les…", s.intervalEnabled) { change { s.intervalEnabled = it } }
                if (s.intervalEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        INTERVALS.forEach { m ->
                            FilterChip(
                                selected = s.intervalMinutes == m,
                                onClick = { change { s.intervalMinutes = m } },
                                label = { Text(intervalLabel(m)) }
                            )
                        }
                    }
                }
                SwitchRow("À heures fixes", s.hoursEnabled) { change { s.hoursEnabled = it } }
                if (s.hoursEnabled) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (0..23).forEach { h ->
                            FilterChip(
                                selected = h in s.hours,
                                onClick = { change { s.hours = if (h in s.hours) s.hours - h else s.hours + h } },
                                label = { Text("${h}h") }
                            )
                        }
                    }
                }
                SwitchRow("Double-tap sur le bureau", s.doubleTap) { change { s.doubleTap = it } }
                SwitchRow("Secouer le téléphone", s.shake) { change { s.shake = it } }
                SwitchRow("À chaque mise en veille", s.screenOff) { change { s.screenOff = it } }
                Text(
                    "Astuce : ajoute la tuile « Fond suivant » dans les réglages rapides.",
                    style = MaterialTheme.typography.bodySmall
                )

                Section("Affichage")
                SwitchRow("Ordre aléatoire", s.shuffle) { change { s.shuffle = it } }
                SwitchRow("Transition en fondu", s.transition) { change { s.transition = it } }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    FillMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = s.fillMode == m,
                            onClick = { change { s.fillMode = m } },
                            shape = SegmentedButtonDefaults.itemShape(i, FillMode.entries.size)
                        ) { Text(m.label) }
                    }
                }
            }
        }
    }
}

private fun folderName(uri: String) =
    runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uri)).substringAfter(':').ifEmpty { "/" } }
        .getOrDefault(uri)

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}

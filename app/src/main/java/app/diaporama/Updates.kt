package app.diaporama

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** [apkUrl] et [sha256] : de quoi se mettre à jour seul ; null si la release n'a pas d'APK vérifiable. */
data class Release(val version: String, val url: String, val apkUrl: String? = null, val sha256: String? = null)

/** Dernière release publiée sur GitHub et comparaison avec la version installée (pur). */
object UpdateCheck {
    const val LATEST_URL = "https://api.github.com/repos/KrakenAgite/diaporama/releases/latest"
    const val RELEASES_PAGE = "https://github.com/KrakenAgite/diaporama/releases"
    fun parse(json: String): Release? = runCatching {
        val root = JSONObject(json)
        if (root.optBoolean("draft") || root.optBoolean("prerelease")) return null
        val tag = root.getString("tag_name").removePrefix("v")
        val url = root.optString("html_url").takeIf { it.startsWith("https://github.com/") } ?: RELEASES_PAGE
        val assets = root.optJSONArray("assets")
        val apk = assets?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } }
        val apkUrl = apk?.optString("browser_download_url")?.takeIf { it.startsWith("https://") }
        val sha = apk?.optString("digest")?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")?.lowercase()
        // Sans empreinte publiée, pas d'installation : on ne saurait pas vérifier le fichier
        if (apkUrl != null && sha != null) Release(tag, url, apkUrl, sha) else Release(tag, url)
    }.getOrNull()

    /** Le fichier téléchargé a-t-il l'empreinte publiée ? */
    fun matches(input: InputStream, sha256: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == sha256.lowercase()
    }

    /** « 1.10 » est plus récent que « 1.9.3 » ; une version illisible ne l'est jamais. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = numbers(candidate) ?: return false
        val b = numbers(installed) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun numbers(version: String): List<Int>? =
        version.trim().split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
}

/**
 * Regarde sur GitHub s'il existe une version plus récente, à chaque ouverture de l'app et juste après
 * une mise à jour (jamais en arrière-plan sinon). Si l'installation automatique est permise, télécharge l'APK, vérifie son empreinte
 * SHA-256 publiée et le confie à l'installeur d'Android (qui vérifie aussi la signature) ; sinon une notification,
 * une seule fois par version.
 */
class Updater(context: Context) {
    private val app = context.applicationContext
    private val settings = Settings(app)

    val installed: String =
        runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()

    /** « Installer des applis inconnues » autorisé pour Diaporama. */
    fun canInstall(): Boolean = app.packageManager.canRequestPackageInstalls()

    fun canNotify(): Boolean = app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun unknownSourcesIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + app.packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** [force] : bouton « Vérifier maintenant ». Renvoie la release plus récente, s'il y en a une. */
    suspend fun check(force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        if (!force && !settings.updatesEnabled) return@withContext null
        val release = fetchLatest() ?: return@withContext null
        val newer = release.takeIf { UpdateCheck.isNewer(it.version, installed) }
        settings.updatesLastCheck = System.currentTimeMillis()
        settings.updatesLatest = newer?.version
        if (newer == null) return@withContext null
        val done = settings.autoInstall && canInstall() && installOnce(newer)
        if (!done && settings.updatesNotified != newer.version && notifyAvailable(newer)) settings.updatesNotified = newer.version
        newer
    }

    /** Bouton « Installer » : télécharge et installe tout de suite. */
    suspend fun installNow(): Boolean = withContext(Dispatchers.IO) {
        val release = fetchLatest() ?: return@withContext false
        UpdateCheck.isNewer(release.version, installed) && installOnce(release)
    }

    /** Une seule installation à la fois (vérification au retour dans l'app et bouton en même temps). */
    private suspend fun installOnce(release: Release): Boolean = installing.withLock { install(release) }

    private fun fetchLatest(): Release? = runCatching {
        val conn = (URL(UpdateCheck.LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            if (conn.responseCode != 200) null else UpdateCheck.parse(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun download(url: String, to: File): Boolean = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000 }
        try {
            if (conn.responseCode != 200) return false
            conn.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
            true
        } finally {
            conn.disconnect()
        }
    }.getOrDefault(false)

    private fun install(release: Release): Boolean {
        val url = release.apkUrl ?: return false
        val sha = release.sha256 ?: return false
        val apk = File(app.cacheDir, APK_NAME)
        return runCatching {
            if (!download(url, apk)) error("Téléchargement impossible")
            if (!apk.inputStream().use { UpdateCheck.matches(it, sha) }) error("Empreinte différente de celle publiée")
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(app.packageName)
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val id = installer.createSession(params)
            installer.openSession(id).use { session ->
                session.openWrite("diaporama.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                val result = PendingIntent.getBroadcast(
                    app, id,
                    Intent(app, InstallResultReceiver::class.java).setPackage(app.packageName),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(result.intentSender)
            }
            true
        }.onFailure { Log.w(TAG, "Mise à jour impossible", it) }.getOrDefault(false).also { if (!it) apk.delete() }
    }

    private fun notifyAvailable(release: Release): Boolean {
        if (!canNotify()) return false
        // Sans l'autorisation d'installer : le réglage d'Android à ouvrir ; sinon, toucher lance l'installation
        val open = if (!canInstall()) {
            PendingIntent.getActivity(app, 0, unknownSourcesIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        } else {
            PendingIntent.getBroadcast(
                app, 0,
                Intent(app, InstallNowReceiver::class.java).setPackage(app.packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        notify(
            app, NOTIFICATION_AVAILABLE,
            tr("Diaporama ${release.version} est disponible", "Diaporama ${release.version} is available"),
            if (canInstall()) tr("Touche pour l'installer", "Tap to install it")
            else tr("Touche pour autoriser Diaporama à installer ses mises à jour", "Tap to let Diaporama install its updates"),
            open,
        )
        return true
    }

    companion object {
        const val TAG = "Diaporama"
        const val APK_NAME = "update.apk"
        private const val CHANNEL = "updates"
        private const val NOTIFICATION_AVAILABLE = 1001
        const val NOTIFICATION_READY = 1002

        /** Pour les vérifications lancées depuis l'écran : elles survivent à sa fermeture. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val installing = Mutex()

        fun notify(context: Context, id: Int, title: String, text: String, tap: PendingIntent) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, tr("Mises à jour", "Updates"), NotificationManager.IMPORTANCE_DEFAULT))
            manager.notify(
                id,
                Notification.Builder(context, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build(),
            )
        }
    }
}

/** Juste après une mise à jour de Diaporama (par l'app ou à la main) : y en a-t-il déjà une autre ? */
class UpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        Updater.scope.launch {
            try { Updater(context).check() } finally { pending.finish() }
        }
    }
}

/** Toucher la notification « disponible » : télécharge et installe. */
class InstallNowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Updater.scope.launch {
            try { Updater(context).installNow() } finally { pending.finish() }
        }
    }
}

/** Réponse de l'installeur : confirmation à demander, ou échec à signaler. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                // Au premier plan, la fenêtre de confirmation s'ouvre ; sinon une notification la propose
                if (runCatching { context.startActivity(confirm) }.isFailure) {
                    Updater.notify(
                        context, Updater.NOTIFICATION_READY,
                        tr("Mise à jour de Diaporama prête", "Diaporama update ready"),
                        tr("Touche pour l'installer", "Tap to install it"),
                        PendingIntent.getActivity(context, 1, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
                    )
                }
            }
            PackageInstaller.STATUS_SUCCESS -> File(context.cacheDir, Updater.APK_NAME).delete()
            else -> Log.w(Updater.TAG, "Installation refusée : ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
    }
}

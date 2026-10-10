package app.diaporama

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit

enum class FillMode(private val fr: String, private val en: String) {
    FILL("Remplir", "Fill"), FIT("Ajuster", "Fit"), CENTER("Centrer", "Center");

    val label: String get() = tr(fr, en)
}

/** Réglages partagés entre l'écran de réglages et le fond d'écran (SharedPreferences). */
class Settings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("diaporama", Context.MODE_PRIVATE)

    /** Dossiers choisis via le sélecteur système (URI d'arborescence, accès persistant). */
    var folders: Set<String>
        get() = prefs.getStringSet(K_FOLDERS, emptySet())!!
        set(v) = prefs.edit { putStringSet(K_FOLDERS, v) }

    /** Photos choisies une par une dans le sélecteur système (y compris albums Google Photos). */
    var photos: Set<String>
        get() = prefs.getStringSet(K_PHOTOS, emptySet())!!
        set(v) = prefs.edit { putStringSet(K_PHOTOS, v) }

    /** Albums de la galerie (BUCKET_ID du MediaStore). */
    var albums: Set<String>
        get() = prefs.getStringSet(K_ALBUMS, emptySet())!!
        set(v) = prefs.edit { putStringSet(K_ALBUMS, v) }

    var intervalEnabled by bool(K_INTERVAL_ON, true)
    /** Intervalle en minutes. */
    var intervalMinutes by int(K_INTERVAL_MIN, 15)
    var shake by bool(K_SHAKE, false)
    var screenOff by bool(K_SCREEN_OFF, true)
    var doubleTap by bool(K_DOUBLE_TAP, true)
    var hoursEnabled by bool(K_HOURS_ON, false)

    /** Heures fixes (0..23) auxquelles changer de fond. */
    var hours: Set<Int>
        get() = prefs.getStringSet(K_HOURS, setOf("7", "12", "18", "22"))!!.map { it.toInt() }.toSet()
        set(v) = prefs.edit { putStringSet(K_HOURS, v.map { it.toString() }.toSet()) }

    var shuffle by bool(K_SHUFFLE, true)
    var transition by bool(K_TRANSITION, true)
    var fillMode: FillMode
        get() = FillMode.valueOf(prefs.getString(K_FILL, FillMode.FILL.name)!!)
        set(v) = prefs.edit { putString(K_FILL, v.name) }

    /** Taille, rotation et position propres à une photo (rien d'enregistré si elle n'est pas retouchée). */
    fun transform(uri: String): PhotoTransform =
        prefs.getString(K_TRANSFORM + uri, null)?.let(PhotoTransform::decode) ?: PhotoTransform()

    fun setTransform(uri: String, t: PhotoTransform) = prefs.edit {
        if (t.isIdentity) remove(K_TRANSFORM + uri) else putString(K_TRANSFORM + uri, t.encode())
    }

    private fun bool(key: String, def: Boolean) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) =
            prefs.edit { putBoolean(key, value) }
    }

    private fun int(key: String, def: Int) = object : kotlin.properties.ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) = prefs.getInt(key, def)
        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Int) =
            prefs.edit { putInt(key, value) }
    }

    companion object {
        const val K_FOLDERS = "folders"
        const val K_ALBUMS = "albums"
        const val K_PHOTOS = "photos"
        const val K_INTERVAL_ON = "interval_on"
        const val K_INTERVAL_MIN = "interval_min"
        const val K_SHAKE = "shake"
        const val K_SCREEN_OFF = "screen_off"
        const val K_DOUBLE_TAP = "double_tap"
        const val K_HOURS_ON = "hours_on"
        const val K_HOURS = "hours"
        const val K_SHUFFLE = "shuffle"
        const val K_TRANSITION = "transition"
        const val K_FILL = "fill"
        /** Préfixe des réglages par photo, suivi de l'URI de la photo. */
        const val K_TRANSFORM = "transform:"

        /** Diffusion interne demandant au fond d'écran de passer à la photo suivante. */
        const val ACTION_NEXT = "app.diaporama.NEXT"

        fun requestNext(context: Context) =
            context.sendBroadcast(Intent(ACTION_NEXT).setPackage(context.packageName))
    }
}

package app.diaporama

import android.service.quicksettings.TileService

/** Tuile des réglages rapides : passe au fond suivant. */
class NextTileService : TileService() {
    override fun onClick() = Settings.requestNext(this)
}

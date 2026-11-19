package com.tarvos.clearaudiotile

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick-Settings-Kachel: togelt persist.clearaudio.enabled via su
 * (setprop). audioserver-seitiger Effekt (libclearaudiofx.so) pollt die
 * Property und schaltet sofort durch. Voraussetzung: Magisk-su und das
 * ClearAudioFX-Magisk-Modul (siehe README).
 */
class ClearAudioTileService : TileService() {

    override fun onStartListening() {
        updateTile()
    }

    override fun onTileAdded() {
        updateTile()
    }

    override fun onClick() {
        val cur = Su.prop()
        val next = if (cur == "1") "0" else "1"
        if (Su.setPropEnabled(next == "1")) {
            updateTile()
        } else {
            // su nicht verfuegbar/verweigert: Kachel auf UNAVAILABLE
            qsTile?.let {
                it.state = Tile.STATE_UNAVAILABLE
                it.updateTile()
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when {
            !Su.suAvailable() -> tile.state = Tile.STATE_UNAVAILABLE
            Su.prop() == "1" -> tile.state = Tile.STATE_ACTIVE
            else -> tile.state = Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}

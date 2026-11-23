package org.cf0x.konamiku.system

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cf0x.konamiku.MainActivity
import org.cf0x.konamiku.data.JsonManager
import org.cf0x.konamiku.data.NfcCard
import org.cf0x.konamiku.nfc.EmuCard
import org.cf0x.konamiku.nfc.EmulationController

/**
 * Quick-settings tile (disabled in the manifest). HCE-F only routes to a
 * foreground Activity, so the tile never activates a card by itself: it
 * opens MainActivity, which activates the chosen card the normal way.
 */
class KonamikuTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onClick() {
        super.onClick()
        scope.launch {
            if (EmulationController.session.value != null) {
                EmulationController.deactivate(applicationContext)
                updateTileState()
                return@launch
            }

            val cards = withContext(Dispatchers.IO) { JsonManager(applicationContext).loadCards() }
            when {
                cards.isEmpty() -> Unit
                cards.size == 1 -> launchActivation(cards[0])
                else -> showCardPicker(cards)
            }
        }
    }

    // The Intent overload is the only one below API 34 and throws from 34 on.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchActivation(card: NfcCard) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EmuCard.EXTRA_AUTO_ACTIVATE, card.id)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun showCardPicker(cards: List<NfcCard>) {
        val names = cards.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select Card")
            .setItems(names) { _, which -> launchActivation(cards[which]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()  // TileService.showDialog is deprecated on API 35+ but still works
    }

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = if (EmulationController.session.value != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        updateTileState()
    }
}

package cl.tecnocreativas.budswidget

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import cl.tecnocreativas.budswidget.protocol.NoiseMode

/** Tile de Ajustes rápidos: cada toque alterna ANC → Transparencia → Normal. */
class NoiseTileService : TileService() {

    companion object {
        fun requestUpdate(ctx: Context) {
            runCatching {
                TileService.requestListeningState(ctx, ComponentName(ctx, NoiseTileService::class.java))
            }
        }
    }

    override fun onStartListening() = render()

    override fun onClick() {
        BudsActions.cycle(this) { render() }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val s = BudsState(this)
        val mode = s.shownMode
        tile.label = when (mode) {
            NoiseMode.ANC -> getString(R.string.mode_anc_short)
            NoiseMode.TRANSPARENCY -> getString(R.string.mode_transparency_short)
            NoiseMode.OFF -> getString(R.string.mode_off_short)
            null -> getString(R.string.tile_label)
        }
        tile.subtitle = when {
            s.pending != null -> getString(R.string.status_applying)
            s.error != null -> s.error
            mode == NoiseMode.ANC -> (s.reportedLevel ?: s.ancLevel).label
            else -> s.model.name.removePrefix("OnePlus ")
        }
        tile.icon = Icon.createWithResource(
            this,
            when (mode) {
                NoiseMode.ANC -> R.drawable.ic_mode_anc
                NoiseMode.TRANSPARENCY -> R.drawable.ic_mode_transparency
                else -> R.drawable.ic_mode_off
            },
        )
        tile.state = when (mode) {
            NoiseMode.ANC, NoiseMode.TRANSPARENCY -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}

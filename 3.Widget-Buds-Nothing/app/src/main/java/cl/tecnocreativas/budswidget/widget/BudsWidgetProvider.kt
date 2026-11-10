package cl.tecnocreativas.budswidget.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import cl.tecnocreativas.budswidget.BudsActions
import cl.tecnocreativas.budswidget.BudsState
import cl.tecnocreativas.budswidget.MainActivity
import cl.tecnocreativas.budswidget.R
import cl.tecnocreativas.budswidget.protocol.AncLevel
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import cl.tecnocreativas.budswidget.ui.DotMatrix
import java.util.concurrent.atomic.AtomicBoolean

class BudsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_SET = "cl.tecnocreativas.budswidget.SET_MODE"
        const val ACTION_CYCLE = "cl.tecnocreativas.budswidget.CYCLE"
        const val ACTION_REFRESH = "cl.tecnocreativas.budswidget.REFRESH"
        const val EXTRA_MODE = "mode"

        private const val STALE_MS = 10 * 60 * 1000L

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, BudsWidgetProvider::class.java))
            if (ids.isNotEmpty()) mgr.updateAppWidget(ids, build(ctx))
        }

        /** Vistas adaptables: el launcher elige la que calza con el tamaño del widget. */
        fun build(ctx: Context): RemoteViews = RemoteViews(
            mapOf(
                SizeF(100f, 100f) to small(ctx, BudsState(ctx)),
                SizeF(220f, 100f) to wide(ctx, BudsState(ctx)),
            )
        )

        private fun pending(ctx: Context, action: String, mode: NoiseMode? = null): PendingIntent {
            val intent = Intent(ctx, BudsWidgetProvider::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            mode?.let { intent.putExtra(EXTRA_MODE, it.name) }
            val code = action.hashCode() + (mode?.ordinal ?: 0)
            return PendingIntent.getBroadcast(
                ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        private fun openApp(ctx: Context) = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        private fun dp(ctx: Context, v: Float) = v * ctx.resources.displayMetrics.density

        private fun headlineFor(mode: NoiseMode?) = when (mode) {
            NoiseMode.ANC -> "ANC"
            NoiseMode.TRANSPARENCY -> "TRANSP"
            NoiseMode.OFF -> "NORMAL"
            null -> "- - -"
        }

        private fun subtitle(ctx: Context, s: BudsState): String {
            s.error?.let { return it }
            if (s.pending != null) return ctx.getString(R.string.status_applying)
            return when (s.mode) {
                NoiseMode.ANC -> {
                    val lvl: AncLevel? = s.reportedLevel ?: s.ancLevel.takeIf { it in s.model.ancLevels }
                    ctx.getString(R.string.mode_anc_long) + (lvl?.let { " · ${it.label}" } ?: "")
                }
                NoiseMode.TRANSPARENCY -> ctx.getString(R.string.mode_transparency_long)
                NoiseMode.OFF -> ctx.getString(R.string.mode_off_long)
                null -> ctx.getString(R.string.status_tap_to_sync)
            }
        }

        private fun battery(s: BudsState): String {
            val l = s.batteryLeft; val r = s.batteryRight
            return when {
                l >= 0 && r >= 0 -> "L $l  R $r"
                l >= 0 -> "L $l"
                r >= 0 -> "R $r"
                else -> ""
            }
        }

        private fun title(s: BudsState): String {
            val name = s.model.name.ifEmpty { s.deviceName ?: "Buds" }
            return name.removePrefix("OnePlus ").uppercase()
        }

        private fun setStatusDot(v: RemoteViews, s: BudsState, on: Int, off: Int) {
            val ok = s.error == null && s.linkUp != false && s.mode != null
            v.setViewVisibility(on, if (ok) View.VISIBLE else View.GONE)
            v.setViewVisibility(off, if (ok) View.GONE else View.VISIBLE)
        }

        // ───────────── 4×2: título, estado grande en puntos y tres botones ─────────────

        private fun wide(ctx: Context, s: BudsState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
            val shown = s.shownMode
            v.setTextViewText(R.id.title, title(s))
            v.setTextViewText(R.id.battery, battery(s))
            v.setTextViewText(R.id.subtitle, subtitle(ctx, s))
            setStatusDot(v, s, R.id.status_on, R.id.status_off)
            v.setImageViewBitmap(R.id.headline, DotMatrix.text(headlineFor(shown), dp(ctx, 4.6f), ghostAlpha = 0))

            val buttons = listOf(
                Triple(NoiseMode.ANC, intArrayOf(R.id.btn_anc_idle, R.id.btn_anc_sel), intArrayOf(R.id.ic_anc_idle, R.id.ic_anc_sel)),
                Triple(NoiseMode.TRANSPARENCY, intArrayOf(R.id.btn_tr_idle, R.id.btn_tr_sel), intArrayOf(R.id.ic_tr_idle, R.id.ic_tr_sel)),
                Triple(NoiseMode.OFF, intArrayOf(R.id.btn_off_idle, R.id.btn_off_sel), intArrayOf(R.id.ic_off_idle, R.id.ic_off_sel)),
            )
            val containers = mapOf(NoiseMode.ANC to R.id.btn_anc, NoiseMode.TRANSPARENCY to R.id.btn_tr, NoiseMode.OFF to R.id.btn_off)
            for ((mode, pills, icons) in buttons) {
                val selected = mode == shown
                v.setViewVisibility(pills[0], if (selected) View.GONE else View.VISIBLE)
                v.setViewVisibility(pills[1], if (selected) View.VISIBLE else View.GONE)
                val icon = DotMatrix.icon(mode, dp(ctx, 1.55f))
                icons.forEach { v.setImageViewBitmap(it, icon) }
                v.setOnClickPendingIntent(containers.getValue(mode), pending(ctx, ACTION_SET, mode))
            }
            v.setOnClickPendingIntent(R.id.header, openApp(ctx))
            v.setOnClickPendingIntent(R.id.headline, pending(ctx, ACTION_REFRESH))
            return v
        }

        // ───────────── 2×2: ícono grande, tocar para alternar ─────────────

        private fun small(ctx: Context, s: BudsState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)
            val shown = s.shownMode
            v.setTextViewText(R.id.s_title, title(s).replace("NORD ", ""))
            val bat = listOf(s.batteryLeft, s.batteryRight).filter { it >= 0 }
            v.setTextViewText(R.id.s_battery, if (bat.isEmpty()) "" else "${bat.min()}%")
            setStatusDot(v, s, R.id.s_status_on, R.id.s_status_off)
            v.setImageViewBitmap(R.id.s_icon, DotMatrix.icon(shown ?: NoiseMode.OFF, dp(ctx, 4.2f)))
            v.setImageViewBitmap(R.id.s_headline, DotMatrix.text(headlineFor(shown), dp(ctx, 2.6f)))
            val ind = mapOf(
                NoiseMode.ANC to (R.id.s_ind_anc_on to R.id.s_ind_anc_off),
                NoiseMode.TRANSPARENCY to (R.id.s_ind_tr_on to R.id.s_ind_tr_off),
                NoiseMode.OFF to (R.id.s_ind_off_on to R.id.s_ind_off_off),
            )
            for ((mode, ids) in ind) {
                v.setViewVisibility(ids.first, if (mode == shown) View.VISIBLE else View.GONE)
                v.setViewVisibility(ids.second, if (mode == shown) View.GONE else View.VISIBLE)
            }
            v.setOnClickPendingIntent(android.R.id.background, pending(ctx, ACTION_CYCLE))
            return v
        }
    }

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        mgr.updateAppWidget(ids, build(ctx))
        val s = BudsState(ctx)
        val stale = System.currentTimeMillis() - s.updatedAt > STALE_MS
        if (stale && s.linkUp != false && BudsActions.hasPermission(ctx)) {
            async { BudsActions.refresh(ctx, it) }
        }
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, options: Bundle) {
        mgr.updateAppWidget(id, build(ctx))
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SET -> {
                val mode = intent.getStringExtra(EXTRA_MODE)?.let { runCatching { NoiseMode.valueOf(it) }.getOrNull() } ?: return
                async { BudsActions.setMode(ctx, mode, it) }
            }
            ACTION_CYCLE -> async { BudsActions.cycle(ctx, it) }
            ACTION_REFRESH -> async { BudsActions.refresh(ctx, it) }
            else -> super.onReceive(ctx, intent)
        }
    }

    /** Mantiene vivo el receiver mientras dura la operación Bluetooth (máx. ~8,5 s). */
    private fun async(work: (done: () -> Unit) -> Unit) {
        val result = goAsync()
        val finished = AtomicBoolean(false)
        val finish = { if (finished.compareAndSet(false, true)) result.finish() }
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 8500)
        work { finish() }
    }
}

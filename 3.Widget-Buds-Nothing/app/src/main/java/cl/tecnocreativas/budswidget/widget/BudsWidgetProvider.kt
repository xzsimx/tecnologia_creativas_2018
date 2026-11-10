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
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import cl.tecnocreativas.budswidget.ui.DotMatrix
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Un solo provider con vistas adaptables: según el tamaño que el usuario le dé, el launcher
 * muestra círculo (1x1), cápsula (2x1 / 1x2), tarjeta (2x2) o la versión ancha (4x2).
 * Las subclases de abajo solo existen para que cada tamaño aparezca como su propia
 * entrada en el selector de widgets.
 */
open class BudsWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_SET = "cl.tecnocreativas.budswidget.SET_MODE"
        const val ACTION_CYCLE = "cl.tecnocreativas.budswidget.CYCLE"
        const val ACTION_REFRESH = "cl.tecnocreativas.budswidget.REFRESH"
        const val EXTRA_MODE = "mode"

        private const val STALE_MS = 10 * 60 * 1000L

        fun updateAll(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val views = build(ctx)
            for (cls in PROVIDERS) {
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, cls))
                if (ids.isNotEmpty()) mgr.updateAppWidget(ids, views)
            }
        }

        private val PROVIDERS = listOf(
            BudsWidgetProvider::class.java,
            BudsWidget1x1::class.java,
            BudsWidget2x1::class.java,
            BudsWidget1x2::class.java,
        )

        /**
         * Vistas adaptables. El launcher usa la más grande que quepa en el tamaño real del
         * widget (en dp). En el Nothing Phone (2a) con 4 columnas una celda mide ≈ 103×94 dp.
         */
        fun build(ctx: Context): RemoteViews {
            val s = BudsState(ctx)
            return RemoteViews(
                mapOf(
                    SizeF(40f, 40f) to circle(ctx, s),
                    SizeF(150f, 40f) to capsule(ctx, s, vertical = false),
                    SizeF(40f, 150f) to capsule(ctx, s, vertical = true),
                    SizeF(130f, 130f) to small(ctx, s),
                    SizeF(280f, 130f) to wide(ctx, s),
                )
            )
        }

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

        private fun label(ctx: Context, mode: NoiseMode?) = when (mode) {
            NoiseMode.ANC -> ctx.getString(R.string.label_anc)
            NoiseMode.TRANSPARENCY -> ctx.getString(R.string.label_transparency)
            NoiseMode.OFF -> ctx.getString(R.string.label_off)
            null -> ctx.getString(R.string.status_tap_to_sync)
        }

        /** Texto de estado que reemplaza a la batería cuando hay algo que decir. */
        private fun status(ctx: Context, s: BudsState): String? = when {
            s.pending != null -> ctx.getString(R.string.status_applying)
            s.error != null -> s.error
            s.linkUp == false -> ctx.getString(R.string.status_disconnected)
            else -> null
        }

        private fun title(s: BudsState): String {
            val name = s.model.name.ifEmpty { s.deviceName ?: "Buds" }
            return name.removePrefix("OnePlus ").uppercase()
        }

        /** Cabecera: nombre a la izquierda; batería en puntos (o un estado) a la derecha. */
        private fun header(ctx: Context, v: RemoteViews, s: BudsState, ids: HeaderIds) {
            v.setTextViewText(ids.title, title(s))
            val st = status(ctx, s)
            val bat = listOf(s.batteryLeft, s.batteryRight).filter { it >= 0 }.minOrNull()
            when {
                st != null -> {
                    v.setViewVisibility(ids.battery, View.GONE)
                    v.setViewVisibility(ids.status, View.VISIBLE)
                    v.setTextViewText(ids.status, st)
                }
                bat != null -> {
                    v.setViewVisibility(ids.status, View.GONE)
                    v.setViewVisibility(ids.battery, View.VISIBLE)
                    v.setImageViewBitmap(ids.battery, DotMatrix.text("$bat%", dp(ctx, 2f)))
                }
                else -> {
                    v.setViewVisibility(ids.status, View.GONE)
                    v.setViewVisibility(ids.battery, View.GONE)
                }
            }
            if (ids.dotOn != 0) {
                val ok = s.error == null && s.linkUp != false && s.mode != null
                v.setViewVisibility(ids.dotOn, if (ok) View.VISIBLE else View.GONE)
                v.setViewVisibility(ids.dotOff, if (ok) View.GONE else View.VISIBLE)
            }
        }

        private class HeaderIds(val title: Int, val battery: Int, val status: Int, val dotOn: Int = 0, val dotOff: Int = 0)

        /** Las vistas de cada botón: fondo, ícono y etiqueta, en versión normal y seleccionada. */
        private class ModeIds(val button: Int, val idle: IntArray, val sel: IntArray)

        private val WIDE_MODES = mapOf(
            NoiseMode.ANC to ModeIds(
                R.id.btn_anc,
                intArrayOf(R.id.bg_anc_idle, R.id.ic_anc_idle, R.id.lb_anc_idle),
                intArrayOf(R.id.bg_anc_sel, R.id.ic_anc_sel, R.id.lb_anc_sel),
            ),
            NoiseMode.TRANSPARENCY to ModeIds(
                R.id.btn_tr,
                intArrayOf(R.id.bg_tr_idle, R.id.ic_tr_idle, R.id.lb_tr_idle),
                intArrayOf(R.id.bg_tr_sel, R.id.ic_tr_sel, R.id.lb_tr_sel),
            ),
            NoiseMode.OFF to ModeIds(
                R.id.btn_off,
                intArrayOf(R.id.bg_off_idle, R.id.ic_off_idle, R.id.lb_off_idle),
                intArrayOf(R.id.bg_off_sel, R.id.ic_off_sel, R.id.lb_off_sel),
            ),
        )

        // ───────────── 4×2: nombre + batería, y los tres modos ─────────────

        private fun wide(ctx: Context, s: BudsState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_wide)
            header(ctx, v, s, HeaderIds(R.id.title, R.id.battery, R.id.status, R.id.status_on, R.id.status_off))
            val shown = s.shownMode
            for ((mode, ids) in WIDE_MODES) {
                val selected = mode == shown
                ids.idle.forEach { v.setViewVisibility(it, if (selected) View.GONE else View.VISIBLE) }
                ids.sel.forEach { v.setViewVisibility(it, if (selected) View.VISIBLE else View.GONE) }
                v.setOnClickPendingIntent(ids.button, pending(ctx, ACTION_SET, mode))
                v.setContentDescription(ids.button, label(ctx, mode))
            }
            v.setOnClickPendingIntent(R.id.header, openApp(ctx))
            return v
        }

        // ───────────── 1×1: círculo con el modo actual; tocar para alternar ─────────────

        private fun circle(ctx: Context, s: BudsState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_circle)
            val shown = s.shownMode ?: NoiseMode.OFF
            val icons = mapOf(NoiseMode.ANC to R.id.c_ic_anc, NoiseMode.TRANSPARENCY to R.id.c_ic_tr, NoiseMode.OFF to R.id.c_ic_off)
            for ((mode, id) in icons) v.setViewVisibility(id, if (mode == shown) View.VISIBLE else View.GONE)
            v.setViewVisibility(R.id.c_dot, if (s.shownMode == NoiseMode.ANC) View.VISIBLE else View.GONE)
            v.setContentDescription(R.id.c_root, label(ctx, s.shownMode))
            v.setOnClickPendingIntent(R.id.c_root, pending(ctx, ACTION_CYCLE))
            return v
        }

        // ───────────── 2×1 / 1×2: cápsula con los tres modos ─────────────

        private fun capsule(ctx: Context, s: BudsState, vertical: Boolean): RemoteViews {
            val v = RemoteViews(ctx.packageName, if (vertical) R.layout.widget_pill_v else R.layout.widget_pill_h)
            val ids = if (vertical) {
                mapOf(
                    NoiseMode.ANC to intArrayOf(R.id.pv_btn_anc, R.id.pv_sel_anc, R.id.pv_ic_anc_idle, R.id.pv_ic_anc_sel),
                    NoiseMode.TRANSPARENCY to intArrayOf(R.id.pv_btn_tr, R.id.pv_sel_tr, R.id.pv_ic_tr_idle, R.id.pv_ic_tr_sel),
                    NoiseMode.OFF to intArrayOf(R.id.pv_btn_off, R.id.pv_sel_off, R.id.pv_ic_off_idle, R.id.pv_ic_off_sel),
                )
            } else {
                mapOf(
                    NoiseMode.ANC to intArrayOf(R.id.ph_btn_anc, R.id.ph_sel_anc, R.id.ph_ic_anc_idle, R.id.ph_ic_anc_sel),
                    NoiseMode.TRANSPARENCY to intArrayOf(R.id.ph_btn_tr, R.id.ph_sel_tr, R.id.ph_ic_tr_idle, R.id.ph_ic_tr_sel),
                    NoiseMode.OFF to intArrayOf(R.id.ph_btn_off, R.id.ph_sel_off, R.id.ph_ic_off_idle, R.id.ph_ic_off_sel),
                )
            }
            val shown = s.shownMode
            for ((mode, id) in ids) {
                val (button, sel, iconIdle, iconSel) = id.toList()
                val on = mode == shown
                v.setViewVisibility(sel, if (on) View.VISIBLE else View.GONE)
                v.setViewVisibility(iconSel, if (on) View.VISIBLE else View.GONE)
                v.setViewVisibility(iconIdle, if (on) View.GONE else View.VISIBLE)
                v.setContentDescription(button, label(ctx, mode))
                v.setOnClickPendingIntent(button, pending(ctx, ACTION_SET, mode))
            }
            return v
        }

        // ───────────── 2×2: el modo actual; tocar para alternar ─────────────

        private fun small(ctx: Context, s: BudsState): RemoteViews {
            val v = RemoteViews(ctx.packageName, R.layout.widget_small)
            header(ctx, v, s, HeaderIds(R.id.s_title, R.id.s_battery, R.id.s_status))
            val shown = s.shownMode
            val icons = mapOf(NoiseMode.ANC to R.id.s_ic_anc, NoiseMode.TRANSPARENCY to R.id.s_ic_tr, NoiseMode.OFF to R.id.s_ic_off)
            for ((mode, id) in icons) {
                v.setViewVisibility(id, if (mode == (shown ?: NoiseMode.OFF)) View.VISIBLE else View.GONE)
            }
            v.setTextViewText(R.id.s_label, label(ctx, shown))
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

/** Entradas extra en el selector de widgets; mismo comportamiento, otro tamaño inicial. */
class BudsWidget1x1 : BudsWidgetProvider()

class BudsWidget2x1 : BudsWidgetProvider()

class BudsWidget1x2 : BudsWidgetProvider()

package cl.tecnocreativas.budswidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import cl.tecnocreativas.budswidget.bt.BudsClient
import cl.tecnocreativas.budswidget.protocol.AncLevel
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import cl.tecnocreativas.budswidget.ui.DotMatrix
import cl.tecnocreativas.budswidget.widget.BudsWidgetProvider

/** Pantalla principal, al estilo Nothing X: solo batería y control de ruido a la vista. */
class MainActivity : Activity() {

    private lateinit var state: BudsState
    private lateinit var console: TextView
    private lateinit var advanced: View

    private class ModeButton(val circle: FrameLayout, val icon: ImageView, val label: TextView)

    private val modeButtons = mutableMapOf<NoiseMode, ModeButton>()

    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        state = BudsState(this)
        console = findViewById(R.id.console)
        advanced = findViewById(R.id.advanced)

        findViewById<Button>(R.id.grant).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        findViewById<View>(R.id.pin).setOnClickListener { pinWidget() }
        findViewById<View>(R.id.advanced_toggle).setOnClickListener {
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            findViewById<TextView>(R.id.advanced_chevron).rotation = if (advanced.visibility == View.VISIBLE) 90f else 0f
            renderConsole()
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { BudsActions.refresh(this, ::render) }
        val raw = findViewById<EditText>(R.id.raw)
        findViewById<Button>(R.id.send_raw).setOnClickListener {
            BudsActions.sendRaw(this, raw.text.toString(), ::render)
        }

        buildModeButtons()
    }

    override fun onResume() {
        super.onResume()
        BudsActions.onLog = ::renderConsole
        render()
        renderConsole()
        if (BudsActions.hasPermission(this)) fillDevices()
    }

    override fun onPause() {
        BudsActions.onLog = null
        super.onPause()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (BudsActions.hasPermission(this)) {
            fillDevices()
            BudsActions.refresh(this, ::render)
        }
        render()
    }

    // ───────────────────────── control de ruido ─────────────────────────

    private fun buildModeButtons() {
        val row = findViewById<LinearLayout>(R.id.modes)
        val modes = listOf(
            Triple(NoiseMode.ANC, R.drawable.ic_mode_anc, R.string.label_anc),
            Triple(NoiseMode.TRANSPARENCY, R.drawable.ic_mode_transparency, R.string.label_transparency),
            Triple(NoiseMode.OFF, R.drawable.ic_mode_off, R.string.label_off),
        )
        for ((mode, iconRes, labelRes) in modes) {
            val column = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val size = (60 * dp).toInt()
            val circle = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
                isClickable = true
                isFocusable = true
                contentDescription = getString(labelRes)
                setOnClickListener {
                    BudsActions.setMode(this@MainActivity, mode, ::render)
                    render()
                }
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = FrameLayout.LayoutParams((26 * dp).toInt(), (26 * dp).toInt(), Gravity.CENTER)
            }
            circle.addView(icon)
            val label = TextView(this).apply {
                setText(labelRes)
                textSize = 12f
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            column.addView(circle)
            column.addView(label)
            row.addView(column)
            modeButtons[mode] = ModeButton(circle, icon, label)
        }
    }

    private fun renderModes() {
        val shown = state.shownMode
        val fg = getColor(R.color.fg)
        val fg2 = getColor(R.color.fg_secondary)
        val onSel = getColor(R.color.on_sel)
        for ((mode, b) in modeButtons) {
            val sel = mode == shown
            b.circle.setBackgroundResource(if (sel) R.drawable.circle_sel else R.drawable.circle_idle)
            b.icon.imageTintList = ColorStateList.valueOf(if (sel) onSel else fg)
            b.label.setTextColor(if (sel) fg else fg2)
            b.label.typeface = if (sel) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
        }
        renderLevels(shown)
    }

    /** Selector segmentado del nivel de ANC; solo aparece con ANC activo. */
    private fun renderLevels(shown: NoiseMode?) {
        val group = findViewById<LinearLayout>(R.id.levels)
        val levels = AncLevel.entries.filter { it in state.model.ancLevels }
        if (shown != NoiseMode.ANC || levels.size < 2) {
            group.visibility = View.GONE
            return
        }
        group.visibility = View.VISIBLE
        group.removeAllViews()
        val current = state.reportedLevel ?: state.ancLevel
        for (lvl in levels) {
            val sel = lvl == current
            group.addView(TextView(this).apply {
                text = SHORT_LEVEL.getValue(lvl)
                gravity = Gravity.CENTER
                textSize = 12f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(getColor(if (sel) R.color.on_sel else R.color.fg_secondary))
                if (sel) setBackgroundResource(R.drawable.pill_sel)
                contentDescription = lvl.label
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    state.ancLevel = lvl
                    state.reportedLevel = lvl
                    BudsActions.setMode(this@MainActivity, NoiseMode.ANC, ::render)
                    render()
                }
            })
        }
    }

    // ───────────────────────── render general ─────────────────────────

    private fun render() {
        val granted = BudsActions.hasPermission(this)
        findViewById<View>(R.id.grant).visibility = if (granted) View.GONE else View.VISIBLE

        findViewById<TextView>(R.id.name).text =
            state.model.name.ifEmpty { state.deviceName ?: "OnePlus Buds 4" }

        val ok = state.error == null && state.linkUp != false && state.mode != null
        findViewById<ImageView>(R.id.link_dot)
            .setImageResource(if (ok) R.drawable.dot_red else R.drawable.dot_grey)
        findViewById<TextView>(R.id.link_text).text = when {
            state.pending != null -> getString(R.string.status_applying)
            state.error != null -> state.error
            state.linkUp == false -> getString(R.string.status_disconnected)
            state.mode == null -> getString(R.string.status_tap_to_sync)
            else -> getString(R.string.status_connected)
        }

        val pitch = 4.2f * dp
        listOf(
            R.id.bat_l to state.batteryLeft,
            R.id.bat_r to state.batteryRight,
            R.id.bat_c to state.batteryCase,
        ).forEach { (id, v) ->
            findViewById<ImageView>(id).setImageBitmap(DotMatrix.text(if (v >= 0) "$v" else "--", pitch))
        }
        renderModes()
    }

    private fun renderConsole() {
        if (advanced.visibility != View.VISIBLE) return
        console.text = synchronized(BudsActions.log) { BudsActions.log.takeLast(60).joinToString("\n") }
    }

    @SuppressLint("MissingPermission")
    private fun fillDevices() {
        val group = findViewById<RadioGroup>(R.id.devices)
        group.setOnCheckedChangeListener(null)
        group.removeAllViews()
        val devices = BudsClient.bondedCandidates(this)
        if (devices.isEmpty()) {
            group.addView(TextView(this).apply { setText(R.string.no_bonded); setTextAppearance(R.style.Caption) })
            return
        }
        val selected = state.address ?: devices.firstOrNull { BudsClient.score(it) > 0 }?.address
        devices.forEach { d ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = d.name ?: d.address
                tag = d.address
                isChecked = d.address == selected
                setTextAppearance(R.style.Body)
            })
        }
        group.setOnCheckedChangeListener { g, id ->
            val addr = g.findViewById<View>(id)?.tag as? String ?: return@setOnCheckedChangeListener
            if (addr != state.address) {
                state.address = addr
                state.productId = null // otro dispositivo: volver a identificarlo
                state.mode = null
                BudsActions.refresh(this, ::render)
            }
        }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, BudsWidgetProvider::class.java), null, null)
        }
    }

    companion object {
        private val SHORT_LEVEL = mapOf(
            AncLevel.MAX to "Máx",
            AncLevel.MODERATE to "Media",
            AncLevel.MILD to "Suave",
            AncLevel.SMART to "Intel.",
            AncLevel.ADAPTIVE to "Adapt.",
        )
    }
}

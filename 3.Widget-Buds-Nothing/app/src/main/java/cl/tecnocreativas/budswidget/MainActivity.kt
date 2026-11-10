package cl.tecnocreativas.budswidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import cl.tecnocreativas.budswidget.bt.BudsClient
import cl.tecnocreativas.budswidget.protocol.AncLevel
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import cl.tecnocreativas.budswidget.ui.DotMatrix
import cl.tecnocreativas.budswidget.widget.BudsWidgetProvider

class MainActivity : Activity() {

    private lateinit var state: BudsState
    private lateinit var status: TextView
    private lateinit var console: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        state = BudsState(this)
        status = findViewById(R.id.status)
        console = findViewById(R.id.console)

        val density = resources.displayMetrics.density
        findViewById<ImageView>(R.id.logo).setImageBitmap(DotMatrix.text("BUDS", 6f * density))

        findViewById<Button>(R.id.grant).setOnClickListener {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
        findViewById<Button>(R.id.mode_anc).setOnClickListener { BudsActions.setMode(this, NoiseMode.ANC, ::render) }
        findViewById<Button>(R.id.mode_tr).setOnClickListener { BudsActions.setMode(this, NoiseMode.TRANSPARENCY, ::render) }
        findViewById<Button>(R.id.mode_off).setOnClickListener { BudsActions.setMode(this, NoiseMode.OFF, ::render) }
        findViewById<Button>(R.id.refresh).setOnClickListener { BudsActions.refresh(this, ::render) }
        findViewById<Button>(R.id.pin).setOnClickListener { pinWidget() }

        val raw = findViewById<EditText>(R.id.raw)
        findViewById<Button>(R.id.send_raw).setOnClickListener {
            BudsActions.sendRaw(this, raw.text.toString(), ::render)
        }

        val levels = findViewById<RadioGroup>(R.id.levels)
        AncLevel.entries.forEach { lvl ->
            levels.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = lvl.label
                tag = lvl
                isChecked = lvl == state.ancLevel
                setTextAppearance(R.style.Body)
            })
        }
        levels.setOnCheckedChangeListener { g, id ->
            (g.findViewById<View>(id)?.tag as? AncLevel)?.let { state.ancLevel = it }
        }
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
                text = "${d.name ?: "?"}  ·  ${d.address}"
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

    private fun render() {
        val granted = BudsActions.hasPermission(this)
        findViewById<View>(R.id.grant).visibility = if (granted) View.GONE else View.VISIBLE
        val bat = listOfNotNull(
            state.batteryLeft.takeIf { it >= 0 }?.let { "L $it%" },
            state.batteryRight.takeIf { it >= 0 }?.let { "R $it%" },
            state.batteryCase.takeIf { it >= 0 }?.let { "Estuche $it%" },
        ).joinToString("   ")
        status.text = buildString {
            appendLine("Modelo      ${state.model.name.ifEmpty { "desconocido" }}  (id ${state.productId ?: "?"})")
            appendLine("Modo        ${state.shownMode?.name ?: "?"}${state.reportedLevel?.let { " · ${it.label}" } ?: ""}")
            appendLine("Batería     ${bat.ifEmpty { "?" }}")
            append("Enlace      ${when (state.linkUp) { true -> "conectado"; false -> "desconectado"; null -> "?" }}")
            state.error?.let { append("\n⚠ $it") }
        }
    }

    private fun renderConsole() {
        console.text = synchronized(BudsActions.log) { BudsActions.log.takeLast(60).joinToString("\n") }
    }

    private fun pinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, BudsWidgetProvider::class.java), null, null)
        }
    }
}

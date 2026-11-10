package cl.tecnocreativas.budswidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import cl.tecnocreativas.budswidget.bt.BudsClient
import cl.tecnocreativas.budswidget.protocol.AncLevel
import cl.tecnocreativas.budswidget.protocol.Catalog
import cl.tecnocreativas.budswidget.protocol.Cmd
import cl.tecnocreativas.budswidget.protocol.Frame
import cl.tecnocreativas.budswidget.protocol.ModeType
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import cl.tecnocreativas.budswidget.protocol.Parse
import cl.tecnocreativas.budswidget.protocol.Payloads
import cl.tecnocreativas.budswidget.protocol.toHex
import cl.tecnocreativas.budswidget.widget.BudsWidgetProvider
import java.io.IOException
import java.util.concurrent.Executors

/**
 * Operaciones de alto nivel. Se ejecutan en un único hilo para que dos toques seguidos
 * no abran dos sockets RFCOMM a la vez.
 */
object BudsActions {
    private const val TAG = "BudsActions"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Bitácora de tramas para la consola de la app (ingeniería inversa en vivo). */
    val log = ArrayDeque<String>()
    var onLog: (() -> Unit)? = null

    private fun log(line: String) {
        Log.d(TAG, line)
        synchronized(log) {
            log.addLast(line)
            while (log.size > 200) log.removeFirst()
        }
        onLog?.let { main.post(it) }
    }

    val CYCLE = listOf(NoiseMode.ANC, NoiseMode.TRANSPARENCY, NoiseMode.OFF)

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    fun setMode(ctx: Context, mode: NoiseMode, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        val state = BudsState(app)
        state.pending = mode
        state.error = null
        notifyUi(app)
        run(app, done) { s ->
            val model = state.model
            val type = model.typeFor(mode, state.ancLevel)
            val bit = model.bitFor(type) ?: throw IOException("Modo no soportado por ${model.name}")
            log("SET ${mode.name} → modeType $type, bit $bit")
            if (s.requestLogged(Cmd.SET_NOISE, Payloads.setNoise(bit)) == null) {
                log("sin ACK de 0x0404 (algunos modelos no responden)")
            }
            // Optimista: si la lectura posterior (0x010C) responde, la sobrescribe.
            state.mode = mode
            state.reportedLevel = AncLevel.fromType(type)
            Thread.sleep(250)
        }
    }

    fun cycle(ctx: Context, done: () -> Unit = {}) {
        val state = BudsState(ctx)
        val current = state.shownMode ?: NoiseMode.OFF
        setMode(ctx, CYCLE[(CYCLE.indexOf(current) + 1) % CYCLE.size], done)
    }

    fun refresh(ctx: Context, done: () -> Unit = {}) {
        val app = ctx.applicationContext
        BudsState(app).error = null
        run(app, done) { }
    }

    /** Envía una trama arbitraria escrita en la consola: "CMD PAYLOAD", p. ej. "0404 01 01 04". */
    fun sendRaw(ctx: Context, input: String, done: () -> Unit = {}) {
        val bytes = input.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).map { it.toInt(16).toByte() }
        if (bytes.size < 2) { log("formato: CMD(2 bytes hex) PAYLOAD…  ej. 010C 0101"); return }
        val cmd = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val payload = bytes.drop(2).toByteArray()
        run(ctx.applicationContext, done, readStatus = false) { s -> s.requestLogged(cmd, payload) }
    }

    // ───────────────────────────────────────────────────────────────────

    private fun run(
        app: Context,
        done: () -> Unit,
        readStatus: Boolean = true,
        block: (BudsClient.Session) -> Unit,
    ) {
        worker.execute {
            val state = BudsState(app)
            try {
                if (!hasPermission(app)) throw IOException(app.getString(R.string.err_permission))
                val adapter = BudsClient.adapter(app)
                if (adapter == null || !adapter.isEnabled) throw IOException(app.getString(R.string.err_bt_off))
                val client = BudsClient(app)
                val device = client.findDevice(state.address)
                    ?: throw IOException(app.getString(R.string.err_no_device))
                state.address = device.address
                state.deviceName = runCatching { device.name }.getOrNull()
                log("conectando a ${state.deviceName} (${device.address})…")
                val session = try {
                    client.open(device)
                } catch (e: IOException) {
                    throw IOException(app.getString(R.string.err_not_connected), e)
                }
                session.use { s ->
                    if (state.productId == null) readProductId(s, state)
                    block(s)
                    if (readStatus) {
                        readNoise(s, state)
                        readBattery(s, state)
                    }
                    s.drain().forEach { log("← push $it") }
                }
                state.linkUp = true
                state.updatedAt = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "fallo", e)
                log("✕ ${e.message}")
                // En pantalla solo un mensaje corto; el detalle técnico queda en la consola.
                state.error = when (e) {
                    is SecurityException -> app.getString(R.string.err_permission)
                    is IOException -> e.message ?: app.getString(R.string.err_not_connected)
                    else -> app.getString(R.string.err_unexpected)
                }
                if (e.message == app.getString(R.string.err_not_connected)) state.linkUp = false
            } finally {
                state.pending = null
                notifyUi(app)
                main.post(done)
            }
        }
    }

    private fun BudsClient.Session.requestLogged(cmd: Int, payload: ByteArray = ByteArray(0)): Frame? {
        log("→ %04X %s".format(cmd, payload.toHex()))
        return request(cmd, payload).also { log(if (it != null) "← $it" else "← (sin respuesta)") }
    }

    private fun readProductId(s: BudsClient.Session, state: BudsState) {
        s.requestLogged(Cmd.PRODUCT_ID)?.let { f ->
            Parse.productId(f.payload)?.let {
                state.productId = it
                log("product id $it → ${Catalog.models[it]?.name ?: "modelo desconocido (genérico)"}")
            }
        }
    }

    private fun readNoise(s: BudsClient.Session, state: BudsState): Boolean {
        val f = s.requestLogged(Cmd.NOISE_QUERY, Payloads.NOISE_QUERY) ?: return false
        val bit = Parse.noiseBit(f.payload) ?: return false
        val type = state.model.reportedBits[bit] ?: run { log("bit $bit desconocido"); return false }
        state.mode = ModeType.toMode(type)
        state.reportedLevel = AncLevel.fromType(type)
        log("modo actual: ${state.mode} (bit $bit, modeType $type)")
        return true
    }

    private fun readBattery(s: BudsClient.Session, state: BudsState) {
        val f = s.requestLogged(Cmd.BATTERY) ?: return
        val b = Parse.battery(f.payload) ?: return
        state.batteryLeft = b.left ?: -1
        state.batteryRight = b.right ?: -1
        state.batteryCase = b.case ?: -1
    }

    fun notifyUi(ctx: Context) {
        BudsWidgetProvider.updateAll(ctx)
        NoiseTileService.requestUpdate(ctx)
    }
}

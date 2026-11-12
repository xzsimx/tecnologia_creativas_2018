package cl.tecnocreativas.budswidget

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import cl.tecnocreativas.budswidget.bt.BudsClient
import cl.tecnocreativas.budswidget.protocol.Headset
import cl.tecnocreativas.budswidget.protocol.NoiseMode
import cl.tecnocreativas.budswidget.widget.BudsWidgetProvider
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operaciones de alto nivel, iguales para todas las marcas (cada una tiene su [Headset]).
 * Se ejecutan en un único hilo para que dos toques seguidos no abran dos sockets a la vez.
 */
object BudsActions {
    private const val TAG = "BudsActions"
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Bitácora de tramas para la consola de la app (ingeniería inversa en vivo). */
    val log = ArrayDeque<String>()
    @Volatile var onLog: (() -> Unit)? = null

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
        run(app, done) { hs ->
            val level = state.ancLevel.takeIf { it in state.ancLevels }
            // Optimista: si la lectura posterior responde, la sobrescribe.
            val applied = hs.setMode(mode, level)
            state.mode = applied.mode
            state.reportedLevel = applied.level
            Thread.sleep(250)
        }
    }

    fun cycle(ctx: Context, done: () -> Unit = {}) {
        val state = BudsState(ctx)
        val current = state.shownMode ?: NoiseMode.OFF
        setMode(ctx, CYCLE[(CYCLE.indexOf(current) + 1) % CYCLE.size], done)
    }

    private val refreshing = AtomicBoolean(false)

    /**
     * Lee modo y batería. Si ya hay una lectura en curso no abre otra conexión (varios
     * widgets, el tile y la app pueden pedirla a la vez).
     */
    fun refresh(ctx: Context, done: () -> Unit = {}) {
        if (!refreshing.compareAndSet(false, true)) { main.post(done); return }
        val app = ctx.applicationContext
        BudsState(app).error = null
        run(app, { refreshing.set(false); done() }) { }
    }

    /**
     * Refresca solo si el último dato tiene más de [maxAgeMs] y los audífonos no están
     * desconectados. Así abrir el panel o la app no despierta la radio a cada rato.
     */
    fun refreshIfStale(ctx: Context, maxAgeMs: Long, done: () -> Unit = {}) {
        val s = BudsState(ctx)
        val stale = System.currentTimeMillis() - s.updatedAt > maxAgeMs
        if (stale && s.linkUp != false && s.pending == null && hasPermission(ctx)) refresh(ctx, done)
        else main.post(done)
    }

    /** Envía un comando crudo escrito en la consola: "CMD PAYLOAD", p. ej. "0404 01 01 04". */
    fun sendRaw(ctx: Context, input: String, done: () -> Unit = {}) {
        val bytes = input.replace(Regex("[^0-9A-Fa-f]"), "").chunked(2).map { it.toInt(16).toByte() }
        if (bytes.size < 2) { log("formato: CMD(2 bytes hex) PAYLOAD…  ej. 010C 0101"); return }
        val cmd = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val payload = bytes.drop(2).toByteArray()
        run(ctx.applicationContext, done, readStatus = false) { hs -> hs.raw(cmd, payload) }
    }

    // ───────────────────────────────────────────────────────────────────

    private fun run(
        app: Context,
        done: () -> Unit,
        readStatus: Boolean = true,
        block: (Headset) -> Unit,
    ) {
        worker.execute {
            val state = BudsState(app)
            try {
                if (!hasPermission(app)) throw IOException(app.getString(R.string.err_permission))
                val adapter = BudsClient.adapter(app)
                if (adapter == null || !adapter.isEnabled) throw IOException(app.getString(R.string.err_bt_off))
                val client = BudsClient(app)
                val target = client.findTarget(state.pinned, state.address)
                    ?: throw IOException(app.getString(R.string.err_no_device))
                val address = target.device.address
                state.switchTo(address, runCatching { target.device.name }.getOrNull())
                log("conectando a ${state.deviceName} (${target.family.label}, $address)…")
                val link = try {
                    client.open(target)
                } catch (e: IOException) {
                    throw IOException(app.getString(R.string.err_not_connected), e)
                }
                link.use {
                    val hs = target.family.create(link, ::log)
                    state.saveIdentity(address, hs.identify(state.productIdFor(address)))
                    block(hs)
                    if (readStatus) {
                        hs.readMode()?.let {
                            state.mode = it.mode
                            state.reportedLevel = it.level
                            log("modo actual: ${it.mode}${it.level?.let { l -> " · ${l.label}" } ?: ""}")
                        }
                        hs.readBattery()?.let(state::setBattery)
                    }
                    hs.pushes().forEach { log("← aviso $it") }
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

    fun notifyUi(ctx: Context) {
        BudsWidgetProvider.updateAll(ctx)
        NoiseTileService.requestUpdate(ctx)
    }
}

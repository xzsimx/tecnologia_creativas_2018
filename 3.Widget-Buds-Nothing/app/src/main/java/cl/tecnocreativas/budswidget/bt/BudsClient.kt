package cl.tecnocreativas.budswidget.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import cl.tecnocreativas.budswidget.protocol.Family
import cl.tecnocreativas.budswidget.protocol.Transport
import cl.tecnocreativas.budswidget.protocol.toHex
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Encuentra los audífonos y abre una conexión RFCOMM corta con ellos. El audio
 * (A2DP / LE Audio) no se ve afectado: RFCOMM es un canal de control aparte.
 *
 * Todo es bloqueante: llamar siempre desde un hilo de fondo.
 */
@SuppressLint("MissingPermission") // los llamadores verifican BLUETOOTH_CONNECT
class BudsClient(private val context: Context) {

    data class Target(val device: BluetoothDevice, val family: Family, val score: Int)

    companion object {
        private const val TAG = "BudsClient"
        private val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        fun adapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        fun target(d: BluetoothDevice): Target? {
            val name = runCatching { d.name }.getOrNull()
            val uuids = runCatching { d.uuids?.map { it.uuid } }.getOrNull().orEmpty()
            val (family, score) = Family.detect(name, uuids) ?: return null
            return Target(d, family, score)
        }

        /** Audífonos emparejados que la app sabe controlar, los más seguros primero. */
        fun candidates(context: Context): List<Target> =
            adapter(context)?.bondedDevices.orEmpty().mapNotNull(::target).sortedByDescending { it.score }
    }

    /**
     * Elige con qué audífonos hablar: el fijado por el usuario; si no, los que estén
     * conectados como audio ahora (prefiriendo los últimos usados); si no, los últimos usados.
     */
    fun findTarget(pinned: String?, last: String?): Target? {
        val adapter = adapter(context) ?: return null
        if (!adapter.isEnabled) return null
        val all = candidates(context)
        if (pinned != null) all.firstOrNull { it.device.address == pinned }?.let { return it }
        if (all.size > 1) {
            // Solo vale la pena preguntar qué está conectado si hay más de un candidato.
            val connected = connectedAddresses()
            val live = all.filter { it.device.address in connected }
            if (live.isNotEmpty()) return live.firstOrNull { it.device.address == last } ?: live.first()
        }
        return all.firstOrNull { it.device.address == last } ?: all.firstOrNull()
    }

    /** Direcciones conectadas como audio (A2DP, manos libres o LE Audio). Máx. ~1,5 s. */
    private fun connectedAddresses(): Set<String> {
        val adapter = adapter(context) ?: return emptySet()
        val profiles = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= 33) add(BluetoothProfile.LE_AUDIO)
        }
        val result = ConcurrentHashMap.newKeySet<String>()
        val latch = CountDownLatch(profiles.size)
        for (p in profiles) {
            val requested = adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runCatching { proxy.connectedDevices.forEach { result += it.address } }
                    runCatching { adapter.closeProfileProxy(profile, proxy) }
                    latch.countDown()
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }, p)
            if (!requested) latch.countDown()
        }
        latch.await(1500, TimeUnit.MILLISECONDS)
        return result
    }

    fun open(t: Target): Link {
        // Una búsqueda activa vuelve lenta la conexión, pero detenerla exige BLUETOOTH_SCAN
        // (Android 12+). La app no escanea, así que solo lo hacemos si ese permiso existe.
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            runCatching { adapter(context)?.cancelDiscovery() }
        }
        val device = t.device
        val advertised = runCatching { device.uuids?.map { it.uuid } }.getOrNull().orEmpty()
        // primero lo que el dispositivo anuncia, luego el resto como respaldo
        val order = (t.family.uuids.filter { it in advertised } + t.family.uuids +
            (if (t.family == Family.HEYMELODY && UUID_SPP in advertised) listOf(UUID_SPP) else emptyList())
            ).distinct()

        var last: IOException? = null
        // Nothing acepta una sola conexión de control a la vez: si otra app la acaba de
        // soltar, el primer intento puede fallar al instante. Se reintenta una vez.
        repeat(2) { attempt ->
            for (uuid in order) {
                val socket = try {
                    device.createRfcommSocketToServiceRecord(uuid)
                } catch (e: IOException) { last = e; continue }
                val t0 = System.currentTimeMillis()
                try {
                    socket.connect()
                    Log.i(TAG, "RFCOMM conectado vía $uuid")
                    return Link(socket)
                } catch (e: IOException) {
                    Log.w(TAG, "falló $uuid: ${e.message}")
                    last = e
                    runCatching { socket.close() }
                    // Un fallo lento = timeout de paginación: los audífonos no están al alcance
                    // (en el estuche, apagados). No tiene sentido seguir probando.
                    if (System.currentTimeMillis() - t0 > 3000) throw e
                }
            }
            // Último recurso: canal directo (API oculta, puede estar bloqueada).
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                val socket = m.invoke(device, t.family.channel) as BluetoothSocket
                try {
                    socket.connect()
                    Log.i(TAG, "RFCOMM conectado por canal ${t.family.channel}")
                    return Link(socket)
                } catch (e: IOException) {
                    runCatching { socket.close() }
                    throw e
                }
            } catch (e: Exception) {
                Log.w(TAG, "canal ${t.family.channel} directo falló: ${e.message}")
            }
            if (attempt == 0) Thread.sleep(800)
        }
        throw last ?: IOException("No se pudo abrir RFCOMM")
    }

    /** Socket RFCOMM abierto, visto como [Transport] por los protocolos. */
    class Link(private val socket: BluetoothSocket) : Transport, AutoCloseable {
        private val input = socket.inputStream
        private val output = socket.outputStream

        override fun write(bytes: ByteArray) {
            Log.d(TAG, "→ ${bytes.toHex()}")
            output.write(bytes)
            output.flush()
        }

        override fun read(buf: ByteArray, timeoutMs: Long): Int {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val n = input.read(buf)
                    if (n < 0) throw IOException("socket cerrado")
                    return n
                }
                Thread.sleep(15)
            }
            return 0
        }

        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }
}

package cl.tecnocreativas.budswidget.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import cl.tecnocreativas.budswidget.protocol.Cmd
import cl.tecnocreativas.budswidget.protocol.Frame
import cl.tecnocreativas.budswidget.protocol.FrameParser
import cl.tecnocreativas.budswidget.protocol.toHex
import java.io.IOException
import java.util.UUID

/**
 * Conexión RFCOMM corta con los audífonos: abrir, intercambiar unas tramas y cerrar.
 * El audio (A2DP/LE Audio) no se ve afectado: RFCOMM es un canal de control aparte.
 *
 * Todo es bloqueante: llamar siempre desde un hilo de fondo.
 */
@SuppressLint("MissingPermission") // los llamadores verifican BLUETOOTH_CONNECT
class BudsClient(private val context: Context) {

    companion object {
        private const val TAG = "BudsClient"

        /** Servicio SDP "HeyMelody" que anuncian los Buds 4 (canal RFCOMM 15). */
        val UUID_HEYMELODY: UUID = UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5")
        /** Variante que anuncian otras generaciones. */
        val UUID_HEYMELODY_ALT: UUID = UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5")
        /** Puerto serie estándar (algunos realme). */
        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")

        private val NAME_HINTS = listOf("oneplus buds", "oneplus nord buds", "buds 4", "enco", "realme buds")

        fun adapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        fun bondedCandidates(context: Context): List<BluetoothDevice> {
            val bonded = adapter(context)?.bondedDevices.orEmpty()
            return bonded.sortedByDescending { score(it) }
        }

        fun score(d: BluetoothDevice): Int {
            val name = d.name?.lowercase().orEmpty()
            var s = 0
            if (NAME_HINTS.any { name.contains(it) }) s += 2
            if (name.contains("buds 4")) s += 1
            val uuids = d.uuids?.map { it.uuid }.orEmpty()
            if (UUID_HEYMELODY in uuids || UUID_HEYMELODY_ALT in uuids) s += 3
            return s
        }
    }

    /** Busca los audífonos: el elegido en ajustes, o el mejor candidato emparejado. */
    fun findDevice(savedAddress: String?): BluetoothDevice? {
        val adapter = adapter(context) ?: return null
        if (!adapter.isEnabled) return null
        if (savedAddress != null) {
            adapter.bondedDevices.firstOrNull { it.address == savedAddress }?.let { return it }
        }
        return bondedCandidates(context).firstOrNull { score(it) > 0 }
    }

    fun open(device: BluetoothDevice): Session {
        adapter(context)?.cancelDiscovery()
        val advertised = device.uuids?.map { it.uuid }.orEmpty()
        // primero lo que el dispositivo anuncia, luego el resto como respaldo
        val order = mutableListOf<UUID>()
        for (u in listOf(UUID_HEYMELODY, UUID_HEYMELODY_ALT, UUID_SPP)) if (u in advertised) order += u
        for (u in listOf(UUID_HEYMELODY, UUID_HEYMELODY_ALT)) if (u !in order) order += u
        var last: IOException? = null
        for (uuid in order) {
            val socket = try {
                device.createRfcommSocketToServiceRecord(uuid)
            } catch (e: IOException) { last = e; continue }
            val t0 = System.currentTimeMillis()
            try {
                socket.connect()
                Log.i(TAG, "RFCOMM conectado vía $uuid")
                return Session(socket)
            } catch (e: IOException) {
                Log.w(TAG, "falló $uuid: ${e.message}")
                last = e
                runCatching { socket.close() }
                // Un fallo lento = timeout de paginación: los audífonos no están al alcance
                // (en el estuche, apagados). No tiene sentido probar otros UUID.
                if (System.currentTimeMillis() - t0 > 3000) throw e
            }
        }
        // Último recurso: canal 15 directo (API oculta, puede estar bloqueada).
        try {
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val socket = m.invoke(device, 15) as BluetoothSocket
            socket.connect()
            Log.i(TAG, "RFCOMM conectado por canal 15")
            return Session(socket)
        } catch (e: Exception) {
            Log.w(TAG, "canal 15 directo falló: ${e.message}")
        }
        throw last ?: IOException("No se pudo abrir RFCOMM")
    }

    class Session(private val socket: BluetoothSocket) : AutoCloseable {
        private val input = socket.inputStream
        private val output = socket.outputStream
        private val parser = FrameParser()
        private val pending = ArrayDeque<Frame>()
        private var seq = 0

        /** Envía y devuelve el número de secuencia usado. */
        fun send(command: Int, payload: ByteArray = ByteArray(0)): Int {
            seq = (seq + 1) and 0xFF
            val bytes = Frame(command, seq, payload).encode()
            Log.d(TAG, "→ ${bytes.toHex()}")
            output.write(bytes)
            output.flush()
            return seq
        }

        /** Espera la respuesta a [command] (o cualquier trama que cumpla [match]). */
        fun await(
            timeoutMs: Long = 1500,
            match: (Frame) -> Boolean,
        ): Frame? {
            pending.firstOrNull(match)?.let { pending.remove(it); return it }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < deadline) {
                if (input.available() <= 0) { Thread.sleep(15); continue }
                val n = input.read(buf)
                if (n < 0) throw IOException("socket cerrado")
                for (f in parser.feed(buf, n)) {
                    Log.d(TAG, "← $f")
                    if (match(f)) return f else pending.addLast(f)
                }
            }
            return null
        }

        fun request(command: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 1500): Frame? {
            send(command, payload)
            return await(timeoutMs) { it.command == (command or Cmd.REPLY) }
        }

        /** Tramas recibidas pero no consumidas (p. ej. pushes 0x0204). */
        fun drain(): List<Frame> = pending.toList().also { pending.clear() }

        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { socket.close() }
        }
    }
}

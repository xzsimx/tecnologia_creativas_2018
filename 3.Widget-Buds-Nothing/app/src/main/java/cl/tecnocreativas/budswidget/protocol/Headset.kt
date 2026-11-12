package cl.tecnocreativas.budswidget.protocol

import java.io.IOException
import java.util.UUID

/*
 * Capa común a todas las marcas. Cada familia de audífonos habla su propio protocolo por
 * RFCOMM; la app solo conoce esta interfaz. Todo aquí es Kotlin puro (sin Android), así
 * que se prueba en la JVM con un transporte falso.
 */

/** Canal de bytes ya abierto (un socket RFCOMM en Android, un falso en los tests). */
interface Transport {
    fun write(bytes: ByteArray)

    /** Lee lo disponible esperando hasta [timeoutMs]. Devuelve 0 si no llegó nada. */
    fun read(buf: ByteArray, timeoutMs: Long): Int
}

data class Identity(val id: String?, val name: String, val ancLevels: List<AncLevel>)

data class Reading(val mode: NoiseMode, val level: AncLevel?)

/** Una sesión abierta con unos audífonos. Llamar a [identify] antes que a lo demás. */
interface Headset {
    fun identify(cachedId: String?): Identity

    /** Cambia el modo; devuelve lo aplicado (optimista, [readMode] lo confirma). */
    fun setMode(mode: NoiseMode, level: AncLevel?): Reading

    fun readMode(): Reading?

    fun readBattery(): Battery?

    /** Consola: envía un comando crudo y devuelve la respuesta en texto. */
    fun raw(command: Int, payload: ByteArray): String?

    /** Tramas que llegaron sin pedirlas (avisos del audífono), en texto. */
    fun pushes(): List<String>
}

enum class Family(val label: String, val uuids: List<UUID>, val channel: Int) {
    /** OPPO / OnePlus / realme (app HeyMelody). */
    HEYMELODY(
        "OnePlus",
        listOf(
            UUID.fromString("0000079a-d102-11e1-9b23-00025b00a5a5"),
            UUID.fromString("00001107-d102-11e1-9b23-00025b00a5a5"),
        ),
        15,
    ),

    /** Nothing / CMF (app Nothing X). */
    NOTHING(
        "Nothing",
        listOf(UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")),
        15,
    );

    fun create(t: Transport, log: (String) -> Unit): Headset = when (this) {
        HEYMELODY -> HeyMelodyHeadset(t, log)
        NOTHING -> NothingHeadset(t, log)
    }

    companion object {
        private val NAME_HINTS = mapOf(
            HEYMELODY to listOf("oneplus buds", "oneplus nord buds", "enco", "realme buds"),
            // Sin "nothing" a secas: también se llaman así los teléfonos Nothing.
            NOTHING to listOf("nothing ear", "nothing headphone", "headphone (1)", "cmf buds", "cmf headphone"),
        )

        /** 3 = anuncia el servicio de control, 2 = el nombre lo delata, 0 = no es candidato. */
        fun detect(name: String?, uuids: Collection<UUID>): Pair<Family, Int>? {
            for (f in entries) if (f.uuids.any { it in uuids }) return f to 3
            val n = name?.lowercase().orEmpty()
            for (f in entries) if (NAME_HINTS.getValue(f).any { n.contains(it) }) return f to 2
            return null
        }
    }
}

/** Espera respuestas guardando aparte lo que llegue sin pedirse. */
internal class FrameReader<F>(private val t: Transport, private val feed: (ByteArray, Int) -> List<F>) {
    private val pending = ArrayDeque<F>()

    fun await(timeoutMs: Long, match: (F) -> Boolean): F? {
        pending.firstOrNull(match)?.let { pending.remove(it); return it }
        val deadline = System.currentTimeMillis() + timeoutMs
        val buf = ByteArray(1024)
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return null
            val n = t.read(buf, left)
            if (n <= 0) continue
            for (f in feed(buf, n)) if (match(f)) return f else pending.addLast(f)
        }
    }

    fun drain(): List<F> = pending.toList().also { pending.clear() }
}

// ───────────────────────────── OPPO / OnePlus ─────────────────────────────

class HeyMelodyHeadset(private val t: Transport, private val log: (String) -> Unit) : Headset {
    private val parser = FrameParser()
    private val reader = FrameReader(t) { b, n -> parser.feed(b, n) }
    private var seq = 0
    private var model: DeviceModel = Catalog.BUDS_4

    private fun request(cmd: Int, payload: ByteArray = ByteArray(0)): Frame? {
        seq = (seq + 1) and 0xFF
        log("→ %04X %s".format(cmd, payload.toHex()))
        t.write(Frame(cmd, seq, payload).encode())
        return reader.await(1500) { it.command == (cmd or Cmd.REPLY) }
            .also { log(if (it != null) "← $it" else "← (sin respuesta)") }
    }

    override fun identify(cachedId: String?): Identity {
        val pid = cachedId ?: request(Cmd.PRODUCT_ID)?.let { Parse.productId(it.payload) }
        model = Catalog.model(pid)
        if (cachedId == null) log("product id $pid → ${model.name.ifEmpty { "modelo desconocido (genérico)" }}")
        return Identity(pid, model.name, model.ancLevels)
    }

    override fun setMode(mode: NoiseMode, level: AncLevel?): Reading {
        val type = model.typeFor(mode, level)
        val bit = model.bitFor(type) ?: throw IOException("Modo no soportado")
        log("SET $mode → modeType $type, bit $bit")
        if (request(Cmd.SET_NOISE, Payloads.setNoise(bit)) == null) log("sin ACK (algunos modelos no responden)")
        return Reading(mode, AncLevel.fromType(type))
    }

    override fun readMode(): Reading? {
        val f = request(Cmd.NOISE_QUERY, Payloads.NOISE_QUERY) ?: return null
        val bit = Parse.noiseBit(f.payload) ?: return null
        val type = model.reportedBits[bit] ?: run { log("bit $bit desconocido"); return null }
        return Reading(ModeType.toMode(type), AncLevel.fromType(type))
    }

    override fun readBattery(): Battery? = request(Cmd.BATTERY)?.let { Parse.battery(it.payload) }

    override fun raw(command: Int, payload: ByteArray): String? = request(command, payload)?.toString()

    override fun pushes(): List<String> = reader.drain().map { it.toString() }
}

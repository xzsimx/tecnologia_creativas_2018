package cl.tecnocreativas.budswidget.protocol

/*
 * Protocolo "HeyMelody" de OPPO / OnePlus / realme sobre Bluetooth clásico (RFCOMM).
 *
 * Es el protocolo que usa la app oficial (HeyMelody / Ajustes de OnePlus Buds) para
 * hablar con los audífonos. No está documentado: lo que sigue viene de la ingeniería
 * inversa publicada por Gadgetbridge, BudsControl, oppo-pods y RawS-Music, contrastada
 * entre sí. Ver PROTOCOLO.md (incluye cómo verificarlo con un log HCI snoop).
 *
 * Trama (little endian):
 *
 *   AA  LEN  00 00  CMD_lo CMD_hi  SEQ  PLEN_lo PLEN_hi  PAYLOAD...
 *   │   │    │      │              │    │
 *   │   │    │      │              │    └ largo del payload
 *   │   │    │      │              └ número de secuencia (eco en la respuesta)
 *   │   │    │      └ comando; la respuesta usa el mismo con el bit alto (0x0404 → 0x8404)
 *   │   │    └ reservado (0 en OPPO/OnePlus, 4 en algunos realme)
 *   │   └ largo de lo que viene después del 2º byte = PLEN + 7 (LEB128 si ≥ 0x80)
 *   └ preámbulo
 */

object Cmd {
    const val HELLO = 0x0100           // capacidades (bitmap)
    const val PRODUCT_ID = 0x0103      // → 0x8103: 00 <id 3 bytes LE>
    const val FIRMWARE = 0x0105
    const val BATTERY = 0x0106         // → 0x8106: 00 <n> (<id> <nivel|0x80 si carga>)*
    const val NOISE_QUERY = 0x010C     // payload 01 01 → 0x810C: ... 01 01 <máscara LE>
    const val STATUS_PUSH = 0x0204     // notificaciones espontáneas (01 batería, 03 ruido…)
    const val SUBSCRIBE = 0x0205
    const val SET_NOISE = 0x0404       // payload 01 01 <máscara LE con 1 bit encendido>

    const val REPLY = 0x8000
}

class Frame(val command: Int, val seq: Int, val payload: ByteArray) {

    fun encode(): ByteArray {
        val body = 7 + payload.size
        require(body < 0x80) { "payload demasiado largo para una trama simple" }
        val out = ByteArray(2 + body)
        out[0] = 0xAA.toByte()
        out[1] = body.toByte()
        out[2] = 0
        out[3] = 0
        out[4] = (command and 0xFF).toByte()
        out[5] = ((command shr 8) and 0xFF).toByte()
        out[6] = (seq and 0xFF).toByte()
        out[7] = (payload.size and 0xFF).toByte()
        out[8] = ((payload.size shr 8) and 0xFF).toByte()
        payload.copyInto(out, 9)
        return out
    }

    val isReply get() = command and Cmd.REPLY != 0

    override fun toString() =
        "Frame(cmd=0x%04X seq=%d payload=%s)".format(command, seq, payload.toHex())
}

/** Acumula bytes del socket y los separa en tramas. Tolera tramas partidas y basura. */
class FrameParser {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, len: Int = data.size): List<Frame> {
        buf += data.copyOfRange(0, len)
        val frames = mutableListOf<Frame>()
        var i = 0
        while (i < buf.size) {
            if (buf[i] != 0xAA.toByte()) { i++; continue }
            // largo en LEB128 (las tramas largas, p. ej. listas multipunto, usan 2 bytes)
            var pos = i + 1
            var total = 0
            var shift = 0
            var complete = false
            while (pos < buf.size) {
                val b = buf[pos].toInt() and 0xFF
                total = total or ((b and 0x7F) shl shift)
                pos++
                if (b and 0x80 == 0) { complete = true; break }
                shift += 7
                if (shift > 21) break
            }
            if (!complete) break
            val end = pos + total
            if (end > buf.size) break
            val h = pos + 2 // saltar los 2 bytes reservados
            if (h + 5 <= end) {
                val cmd = u8(buf[h]) or (u8(buf[h + 1]) shl 8)
                val seq = u8(buf[h + 2])
                val plen = u8(buf[h + 3]) or (u8(buf[h + 4]) shl 8)
                val start = h + 5
                frames += Frame(cmd, seq, buf.copyOfRange(start, minOf(start + plen, end)))
            }
            i = end
        }
        buf = buf.copyOfRange(minOf(i, buf.size), buf.size)
        return frames
    }
}

// ───────────────────────────── Modos de ruido ─────────────────────────────

enum class NoiseMode { ANC, TRANSPARENCY, OFF }

/**
 * "modeType" de HeyMelody: qué significa cada bit de la máscara. El bit que lleva cada
 * tipo depende del modelo (ver [DeviceModel]).
 */
object ModeType {
    const val OFF = 1
    const val TRANSPARENCY = 2
    const val MILD = 3
    const val MAX = 4
    const val ANC = 5          // ANC sin niveles (modelos básicos)
    const val VOICE = 6        // "realce de voz", se trata como transparencia
    const val SMART = 7
    const val MODERATE = 8
    const val ADAPTIVE = 10

    fun toMode(type: Int): NoiseMode = when (type) {
        OFF -> NoiseMode.OFF
        TRANSPARENCY, VOICE -> NoiseMode.TRANSPARENCY
        else -> NoiseMode.ANC
    }
}

/** Nivel de cancelación que se usa al pulsar "ANC" en el widget. */
enum class AncLevel(val modeType: Int, val label: String) {
    MAX(ModeType.MAX, "Máxima"),
    MODERATE(ModeType.MODERATE, "Moderada"),
    MILD(ModeType.MILD, "Suave"),
    SMART(ModeType.SMART, "Inteligente"),
    ADAPTIVE(ModeType.ADAPTIVE, "Adaptativa");

    companion object {
        fun fromType(type: Int) = entries.firstOrNull { it.modeType == type }
    }
}

/**
 * @param setBits bit → modeType usado para *cambiar* el modo.
 * @param reportedBits bit → modeType que los audífonos pueden *informar* (a veces distinto:
 *   los Buds 4 se ponen en transparencia con el bit 2 pero informan el bit 8).
 */
data class DeviceModel(
    val name: String,
    val modeTypes: List<Int>,
    val setBits: Map<Int, Int>,
    val reportedBits: Map<Int, Int> = setBits,
) {
    val ancLevels: List<AncLevel> get() = modeTypes.mapNotNull(AncLevel::fromType)

    fun bitFor(type: Int): Int? =
        setBits.filterValues { it == type }.keys.minOrNull()
            ?: reportedBits.filterValues { it == type }.keys.minOrNull()

    /** Tipo a enviar para un modo; en ANC usa el nivel elegido si el modelo lo soporta. */
    fun typeFor(mode: NoiseMode, level: AncLevel?): Int = when (mode) {
        NoiseMode.OFF -> ModeType.OFF
        NoiseMode.TRANSPARENCY -> ModeType.TRANSPARENCY
        NoiseMode.ANC -> {
            val levels = ancLevels
            when {
                levels.isEmpty() -> ModeType.ANC
                level != null && level in levels -> level.modeType
                AncLevel.MAX in levels -> AncLevel.MAX.modeType
                else -> levels.first().modeType
            }
        }
    }
}

object Catalog {
    // Disposición común a casi todos los OnePlus con niveles de ANC (Buds 3, Pro 2/3, Nord Buds 3 Pro…)
    private val LEVELS_BITS = mapOf(0 to 1, 1 to 5, 2 to 2, 3 to 1, 4 to 4, 5 to 8, 6 to 3, 7 to 7, 8 to 2)
    private val BASIC_BITS = mapOf(0 to 5, 1 to 1, 2 to 2)
    private val LEVELS = listOf(1, 2, 3, 4, 7, 8)

    val BUDS_4 = DeviceModel(
        name = "OnePlus Buds 4",
        modeTypes = listOf(1, 2, 3, 4, 7, 8, 10),
        setBits = LEVELS_BITS + (11 to 10),
    )

    /** Product ID (tal como lo devuelve 0x8103, en hex big-endian) → modelo. Solo OnePlus. */
    val models: Map<String, DeviceModel> = mapOf(
        "065414" to BUDS_4,
        "067014" to DeviceModel("OnePlus Nord Buds 4", LEVELS, LEVELS_BITS),
        "066814" to DeviceModel("OnePlus Nord Buds 4 Pro", LEVELS, LEVELS_BITS),
        "063C14" to DeviceModel("OnePlus Buds 3", LEVELS, LEVELS_BITS),
        "064014" to DeviceModel(
            "OnePlus Buds Pro 3", LEVELS, LEVELS_BITS,
            LEVELS_BITS + mapOf(12 to 4, 13 to 8, 14 to 3, 15 to 7),
        ),
        "062014" to DeviceModel("OnePlus Buds Pro 2", LEVELS, LEVELS_BITS),
        "063414" to DeviceModel("OnePlus Buds Pro 2R", LEVELS, LEVELS_BITS),
        "064414" to DeviceModel("OnePlus Nord Buds 3 Pro", LEVELS, LEVELS_BITS),
        "066414" to DeviceModel("OnePlus Buds Ace 3", LEVELS, LEVELS_BITS),
        "065014" to DeviceModel("OnePlus Nord Buds 3", listOf(1, 2, 5), BASIC_BITS),
        "062414" to DeviceModel("OnePlus Nord Buds 2", listOf(1, 2, 5), BASIC_BITS),
        "064C14" to DeviceModel("OnePlus Buds Ace 2", listOf(1, 2, 5), BASIC_BITS),
        "060C14" to DeviceModel(
            "OnePlus Buds Pro", listOf(1, 2, 3, 4, 7),
            mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 7),
        ),
        "061014" to DeviceModel("OnePlus Buds Z2", listOf(1, 2, 3, 4), mapOf(0 to 1, 1 to 2, 2 to 3, 3 to 4)),
    )

    /** Modelo desconocido: solo Off / Transparencia / ANC con la disposición más común. */
    val GENERIC = DeviceModel(
        name = "",
        modeTypes = listOf(1, 2, 5),
        setBits = mapOf(0 to 1, 1 to 5, 2 to 2, 3 to 1, 8 to 2),
        reportedBits = LEVELS_BITS,
    )

    /** Sin product ID todavía, asumimos Buds 4 (el objetivo principal de esta app). */
    fun model(productId: String?): DeviceModel =
        if (productId == null) BUDS_4 else models[productId] ?: GENERIC
}

// ───────────────────────────── Codificar / decodificar ─────────────────────────────

object Payloads {
    /** 01 01 + máscara little-endian con el bit del modo encendido. */
    fun setNoise(bit: Int): ByteArray {
        val mask = 1L shl bit
        val bytes = bit / 8 + 1
        return byteArrayOf(0x01, 0x01) + ByteArray(bytes) { ((mask shr (8 * it)) and 0xFF).toByte() }
    }

    val NOISE_QUERY = byteArrayOf(0x01, 0x01)
}

data class Battery(val left: Int?, val right: Int?, val case: Int?, val charging: Set<Int> = emptySet())

object Parse {
    /** Respuesta 0x8103: 00 b1 b2 b3 → "b3b2b1" */
    fun productId(p: ByteArray): String? =
        if (p.size >= 4 && p[0].toInt() == 0) "%02X%02X%02X".format(u8(p[3]), u8(p[2]), u8(p[1])) else null

    /** Respuesta 0x8106 o push 0x0204 tipo 01: <estado> <n> (<id> <nivel|0x80>)* — id 1 izq, 2 der, 3 estuche. */
    fun battery(p: ByteArray): Battery? {
        if (p.size < 2) return null
        var l: Int? = null; var r: Int? = null; var c: Int? = null
        val charging = mutableSetOf<Int>()
        var i = 2
        while (i + 1 < p.size) {
            val id = u8(p[i]); val raw = u8(p[i + 1])
            i += 2
            if (id == 0xFF) continue
            val level = raw and 0x7F
            if (raw and 0x80 != 0) charging += id
            when (id) {
                1 -> l = level
                2 -> r = level
                3 -> c = level
            }
        }
        return Battery(l, r, c?.takeIf { it > 0 || 3 in charging }, charging)
    }

    /**
     * Respuesta 0x810C o push 0x0204 tipo 03. Busca la secuencia 01 01 <máscara> y devuelve
     * el bit encendido (el menos significativo).
     */
    fun noiseBit(p: ByteArray): Int? {
        for (i in 0 until p.size - 2) {
            if (p[i].toInt() == 1 && p[i + 1].toInt() == 1) {
                var mask = u8(p[i + 2])
                if (i + 3 < p.size) mask = mask or (u8(p[i + 3]) shl 8)
                if (mask != 0) return Integer.numberOfTrailingZeros(mask)
            }
        }
        return null
    }
}

internal fun u8(b: Byte) = b.toInt() and 0xFF

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }

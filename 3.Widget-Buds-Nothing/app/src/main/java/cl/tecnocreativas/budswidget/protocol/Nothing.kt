package cl.tecnocreativas.budswidget.protocol

/*
 * Protocolo de control de Nothing / CMF (el que usa la app Nothing X), sobre RFCOMM canal 15
 * (servicio SDP aeac4a03-dff5-498f-843a-34487cf133eb).
 *
 * Fuente principal: la documentación de nothing-toolbox (MIT), verificada en un Nothing
 * Headphone (1) (modelo B170, firmware 1.0.1.81), contrastada con nothing_helper,
 * nothingx-pc y nothing-ear-controller. Ver PROTOCOLO.md.
 *
 * Trama (little endian):
 *
 *   55  CTRL(2)  CMD(2)  LEN(2)  FSN  PAYLOAD…  CRC16(2)
 *       60 01                                   └ CRC-16/MODBUS de todo lo anterior,
 *                                                 presente si CTRL & 0x20
 *
 *   CMD: 0xC0xx leer, 0xF0xx escribir; la respuesta es el mismo código con el bit 15 en 0
 *   (C01E → 401E, F00F → 700F) y repite el FSN. 0xE0xx = aviso espontáneo (FSN 0).
 *   FSN va de 1 a 255 y vuelve a 1.
 */

object NothingCmd {
    const val BATTERY = 0xC007        // → [n, (id, valor)×n]; bit 7 = cargando
    const val MODEL = 0xC01C          // → id LE: 70 B1 = "B170"
    const val ANC_GET = 0xC01E        // payload 03 → tripletas (tipo, valor, 00)
    const val ANC_SET = 0xF00F        // payload 01 <modo> 00 → 700F
    const val EVENT_ANC = 0xE003
    const val EVENT_BATTERY = 0xE001

    fun replyOf(cmd: Int) = cmd and 0x7FFF
}

/** Valores de modo en el byte de ruido. */
object NothingAnc {
    const val HIGH = 1
    const val MID = 2
    const val LOW = 3
    const val ADAPTIVE = 4
    const val OFF = 5             // modelos antiguos (Ear (1)) usan 0
    const val TRANSPARENCY = 7    // 0xFE también se lee como transparencia

    fun code(mode: NoiseMode, level: AncLevel?): Int = when (mode) {
        NoiseMode.OFF -> OFF
        NoiseMode.TRANSPARENCY -> TRANSPARENCY
        NoiseMode.ANC -> when (level) {
            AncLevel.MODERATE -> MID
            AncLevel.MILD -> LOW
            AncLevel.ADAPTIVE -> ADAPTIVE
            else -> HIGH
        }
    }

    fun reading(code: Int): Reading? = when (code) {
        HIGH -> Reading(NoiseMode.ANC, AncLevel.MAX)
        MID -> Reading(NoiseMode.ANC, AncLevel.MODERATE)
        LOW -> Reading(NoiseMode.ANC, AncLevel.MILD)
        ADAPTIVE -> Reading(NoiseMode.ANC, AncLevel.ADAPTIVE)
        0, OFF -> Reading(NoiseMode.OFF, null)
        TRANSPARENCY, 0xFE -> Reading(NoiseMode.TRANSPARENCY, null)
        else -> null
    }

    /** Niveles que ofrece la app Nothing X: alto, medio, bajo y adaptativo. */
    val LEVELS = listOf(AncLevel.MAX, AncLevel.MODERATE, AncLevel.MILD, AncLevel.ADAPTIVE)
}

object NothingModels {
    /** Id que devuelve C01C → nombre. Solo el Headphone (1) está verificado. */
    val names = mapOf(
        "B170" to "Nothing Headphone (1)",
    )
}

class NothingFrame(val command: Int, val fsn: Int, val payload: ByteArray, val control: Int = 0x0160) {

    fun encode(): ByteArray {
        val hasCrc = control and 0x20 != 0
        val out = ByteArray(8 + payload.size + if (hasCrc) 2 else 0)
        out[0] = 0x55
        out[1] = (control and 0xFF).toByte()
        out[2] = ((control shr 8) and 0xFF).toByte()
        out[3] = (command and 0xFF).toByte()
        out[4] = ((command shr 8) and 0xFF).toByte()
        out[5] = (payload.size and 0xFF).toByte()
        out[6] = ((payload.size shr 8) and 0xFF).toByte()
        out[7] = (fsn and 0xFF).toByte()
        payload.copyInto(out, 8)
        if (hasCrc) {
            val crc = NothingCrc.crc16(out, 8 + payload.size)
            out[out.size - 2] = (crc and 0xFF).toByte()
            out[out.size - 1] = ((crc shr 8) and 0xFF).toByte()
        }
        return out
    }

    override fun toString() = "Frame(cmd=0x%04X fsn=%d payload=%s)".format(command, fsn, payload.toHex())
}

object NothingCrc {
    /** CRC-16/MODBUS: init 0xFFFF, polinomio reflejado 0xA001, sin XOR final. */
    fun crc16(data: ByteArray, len: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until len) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }
}

/** Separa tramas Nothing de un flujo; descarta basura y tramas con CRC inválido. */
class NothingParser {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, len: Int = data.size): List<NothingFrame> {
        buf += data.copyOfRange(0, len)
        val frames = mutableListOf<NothingFrame>()
        var i = 0
        while (true) {
            while (i < buf.size && buf[i] != 0x55.toByte()) i++
            if (buf.size - i < 8) break
            val control = u8(buf[i + 1]) or (u8(buf[i + 2]) shl 8)
            val plen = u8(buf[i + 5]) or (u8(buf[i + 6]) shl 8)
            if (plen > 4096) { i++; continue } // no es una cabecera real
            val hasCrc = control and 0x20 != 0
            val total = 8 + plen + if (hasCrc) 2 else 0
            if (buf.size - i < total) break
            if (hasCrc) {
                val frame = buf.copyOfRange(i, i + total)
                val got = u8(frame[total - 2]) or (u8(frame[total - 1]) shl 8)
                if (got != NothingCrc.crc16(frame, total - 2)) { i++; continue }
            }
            frames += NothingFrame(
                command = u8(buf[i + 3]) or (u8(buf[i + 4]) shl 8),
                fsn = u8(buf[i + 7]),
                payload = buf.copyOfRange(i + 8, i + 8 + plen),
                control = control,
            )
            i += total
        }
        buf = buf.copyOfRange(minOf(i, buf.size), buf.size)
        return frames
    }
}

object NothingParse {
    /** C01C → "B170" (id de 16 bits en little endian). */
    fun model(p: ByteArray): String? =
        if (p.size >= 2) "%04X".format(u8(p[0]) or (u8(p[1]) shl 8)) else null

    /** 401E / E003: tripletas (tipo, valor, 00); tipo 1 = modo actual. */
    fun ancCode(p: ByteArray): Int? {
        var i = 0
        while (i + 1 < p.size) {
            if (p[i].toInt() == 1) return u8(p[i + 1])
            i += 3
        }
        return null
    }

    /** 4007 / E001: [n, (id, valor)×n]; id 2 izq., 3 der., 4 estuche, 6 audífono único. */
    fun battery(p: ByteArray): Battery? {
        if (p.isEmpty()) return null
        var l: Int? = null; var r: Int? = null; var c: Int? = null; var single: Int? = null
        val charging = mutableSetOf<Int>()
        var i = 1
        while (i + 1 < p.size) {
            val id = u8(p[i]); val raw = u8(p[i + 1])
            i += 2
            val level = raw and 0x7F
            if (raw and 0x80 != 0) charging += id
            when (id) {
                2 -> l = level
                3 -> r = level
                4 -> c = level
                6 -> single = level
            }
        }
        return Battery(l, r, c, charging, single)
    }
}

class NothingHeadset(private val t: Transport, private val log: (String) -> Unit) : Headset {
    private val parser = NothingParser()
    private val reader = FrameReader(t) { b, n -> parser.feed(b, n) }
    private var fsn = 0

    private fun request(cmd: Int, payload: ByteArray = ByteArray(0)): NothingFrame? {
        fsn = if (fsn >= 255) 1 else fsn + 1
        val f = fsn
        log("→ %04X %s".format(cmd, payload.toHex()))
        t.write(NothingFrame(cmd, f, payload).encode())
        return reader.await(1500) { it.command == NothingCmd.replyOf(cmd) && it.fsn == f }
            .also { log(if (it != null) "← $it" else "← (sin respuesta)") }
    }

    override fun identify(cachedId: String?): Identity {
        val id = cachedId ?: request(NothingCmd.MODEL)?.let { NothingParse.model(it.payload) }
        val name = id?.let { NothingModels.names[it] } ?: ""
        if (cachedId == null) log("modelo $id → ${name.ifEmpty { "Nothing (no verificado)" }}")
        return Identity(id, name, NothingAnc.LEVELS)
    }

    override fun setMode(mode: NoiseMode, level: AncLevel?): Reading {
        val code = NothingAnc.code(mode, level)
        log("SET $mode → modo $code")
        if (request(NothingCmd.ANC_SET, byteArrayOf(0x01, code.toByte(), 0x00)) == null) {
            log("sin ACK de F00F")
        }
        return NothingAnc.reading(code) ?: Reading(mode, level)
    }

    override fun readMode(): Reading? {
        val f = request(NothingCmd.ANC_GET, byteArrayOf(0x03)) ?: return null
        val code = NothingParse.ancCode(f.payload) ?: return null
        return NothingAnc.reading(code) ?: run { log("modo $code desconocido"); null }
    }

    override fun readBattery(): Battery? = request(NothingCmd.BATTERY)?.let { NothingParse.battery(it.payload) }

    override fun raw(command: Int, payload: ByteArray): String? = request(command, payload)?.toString()

    override fun pushes(): List<String> = reader.drain().map { it.toString() }
}

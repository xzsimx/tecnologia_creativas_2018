package cl.tecnocreativas.budswidget.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class NothingTest {

    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    // Tramas reales capturadas de un Nothing Headphone (1) (nothing-toolbox, PROTOCOL.md)
    private val ancReadRequest = "55 60 01 1e c0 01 00 01 03 9c 89"
    private val ancReadAnswer = "55 60 01 1e 40 06 00 01 01 01 00 02 01 00 47 ea"

    @Test fun crcMatchesModbusCheckValue() {
        assertEquals(0x4B37, NothingCrc.crc16("123456789".toByteArray()))
    }

    @Test fun encodesAncReadExactlyLikeTheDevice() {
        assertArrayEquals(hex(ancReadRequest), NothingFrame(NothingCmd.ANC_GET, 1, hex("03")).encode())
    }

    @Test fun encodesTransparencyLikeCapturedEarFrame() {
        // nothing-ear-controller: "5560010ff00300cb010700c5af" (fsn 0xCB)
        assertArrayEquals(
            hex("55 60 01 0f f0 03 00 cb 01 07 00 c5 af"),
            NothingFrame(NothingCmd.ANC_SET, 0xCB, hex("01 07 00")).encode(),
        )
    }

    @Test fun parsesAnswerAndRejectsBadCrc() {
        val good = hex(ancReadAnswer)
        val bad = good.copyOf().also { it[it.size - 1] = 0 }
        val frames = NothingParser().feed(hex("00 13") + bad + good.copyOfRange(0, 5)) +
            NothingParser().let { p -> p.feed(good.copyOfRange(0, 9)) + p.feed(good.copyOfRange(9, good.size)) }
        assertEquals(1, frames.size)
        assertEquals(0x401E, frames[0].command)
        assertEquals(1, NothingParse.ancCode(frames[0].payload))
    }

    @Test fun mapsModesBothWays() {
        assertEquals(7, NothingAnc.code(NoiseMode.TRANSPARENCY, null))
        assertEquals(5, NothingAnc.code(NoiseMode.OFF, AncLevel.MAX))
        assertEquals(1, NothingAnc.code(NoiseMode.ANC, null))
        assertEquals(4, NothingAnc.code(NoiseMode.ANC, AncLevel.ADAPTIVE))
        assertEquals(1, NothingAnc.code(NoiseMode.ANC, AncLevel.SMART)) // sin equivalente → alto
        assertEquals(Reading(NoiseMode.OFF, null), NothingAnc.reading(0))
        assertEquals(Reading(NoiseMode.TRANSPARENCY, null), NothingAnc.reading(0xFE))
        assertNull(NothingAnc.reading(9))
    }

    @Test fun parsesBatteryAndModel() {
        val b = NothingParse.battery(hex("01 06 55"))!!
        assertEquals(85, b.single)
        val charging = NothingParse.battery(hex("01 06 b2"))!!
        assertEquals(50, charging.single)
        assertEquals(setOf(6), charging.charging)
        assertEquals("B170", NothingParse.model(hex("70 b1")))
    }

    /** Simula un Headphone (1): responde cada petición repitiendo su FSN. */
    private class FakeHeadphone : Transport {
        val sent = mutableListOf<NothingFrame>()
        private val parser = NothingParser()
        private var out = ByteArray(0)
        var mode = 7

        override fun write(bytes: ByteArray) {
            for (f in parser.feed(bytes)) {
                sent += f
                val reply = when (f.command) {
                    NothingCmd.MODEL -> hex2("70 b1")
                    NothingCmd.ANC_GET -> byteArrayOf(1, mode.toByte(), 0, 2, 1, 0)
                    NothingCmd.ANC_SET -> { mode = f.payload[1].toInt(); byteArrayOf(0) }
                    NothingCmd.BATTERY -> hex2("01 06 40")
                    else -> null
                } ?: continue
                if (f.command == NothingCmd.ANC_SET) { // el audífono avisa antes de responder
                    out += NothingFrame(NothingCmd.EVENT_ANC, 0, byteArrayOf(1, mode.toByte(), 0)).encode()
                }
                out += NothingFrame(NothingCmd.replyOf(f.command), f.fsn, reply).encode()
            }
        }

        override fun read(buf: ByteArray, timeoutMs: Long): Int {
            if (out.isEmpty()) { Thread.sleep(minOf(timeoutMs, 5)); return 0 }
            val n = minOf(out.size, 7, buf.size) // en trozos chicos a propósito
            out.copyInto(buf, 0, 0, n)
            out = out.copyOfRange(n, out.size)
            return n
        }

        private fun hex2(s: String) = s.split(" ").map { it.toInt(16).toByte() }.toByteArray()
    }

    @Test fun headsetRoundTrip() {
        val fake = FakeHeadphone()
        val log = mutableListOf<String>()
        val hs = Family.NOTHING.create(fake) { log += it }
        val id = hs.identify(null)
        assertEquals("B170", id.id)
        assertEquals("Nothing Headphone (1)", id.name)
        assertEquals(Reading(NoiseMode.TRANSPARENCY, null), hs.readMode())
        hs.setMode(NoiseMode.ANC, AncLevel.MODERATE)
        assertArrayEquals(hex("01 02 00"), fake.sent.last().payload)
        assertEquals(Reading(NoiseMode.ANC, AncLevel.MODERATE), hs.readMode())
        assertEquals(64, hs.readBattery()!!.single)
        assertEquals(1, hs.pushes().size) // el aviso E003
        // los FSN van 1, 2, 3… y nunca 0
        assertEquals(listOf(1, 2, 3, 4, 5), fake.sent.map { it.fsn })
    }

    @Test fun detectsFamilyByServiceThenByName() {
        val nothingUuid = UUID.fromString("aeac4a03-dff5-498f-843a-34487cf133eb")
        assertEquals(Family.NOTHING to 3, Family.detect("lo que sea", listOf(nothingUuid)))
        assertEquals(Family.NOTHING to 2, Family.detect("Nothing Headphone (1)", emptyList()))
        assertEquals(Family.HEYMELODY to 2, Family.detect("OnePlus Buds 4", emptyList()))
        assertNull(Family.detect("Nothing Phone (2a)", emptyList())) // un teléfono no es candidato
        assertNull(Family.detect("JBL Flip 6", emptyList()))
    }
}

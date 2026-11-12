package cl.tecnocreativas.budswidget.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeyMelodyTest {

    private fun hex(s: String) = s.split(" ").filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()

    @Test fun encodesBatteryRequestLikeGadgetbridge() {
        // Gadgetbridge OppoHeadphonesProtocol.encodeMessage(BATTERY_REQ) con seq 0
        assertArrayEquals(hex("AA 07 00 00 06 01 00 00 00"), Frame(Cmd.BATTERY, 0, ByteArray(0)).encode())
    }

    @Test fun encodesTransparencyOnBuds4() {
        val m = Catalog.BUDS_4
        val bit = m.bitFor(m.typeFor(NoiseMode.TRANSPARENCY, null))!!
        assertEquals(2, bit)
        assertArrayEquals(
            hex("AA 0A 00 00 04 04 01 03 00 01 01 04"),
            Frame(Cmd.SET_NOISE, 1, Payloads.setNoise(bit)).encode(),
        )
    }

    @Test fun buds4ModeBits() {
        val m = Catalog.BUDS_4
        assertEquals(0, m.bitFor(m.typeFor(NoiseMode.OFF, null)))
        assertEquals(7, m.bitFor(m.typeFor(NoiseMode.ANC, AncLevel.SMART)))
        assertEquals(4, m.bitFor(m.typeFor(NoiseMode.ANC, AncLevel.MAX)))
        assertEquals(5, m.bitFor(m.typeFor(NoiseMode.ANC, AncLevel.MODERATE)))
        assertEquals(6, m.bitFor(m.typeFor(NoiseMode.ANC, AncLevel.MILD)))
        assertEquals(11, m.bitFor(m.typeFor(NoiseMode.ANC, AncLevel.ADAPTIVE)))
        // sin nivel elegido → Máxima
        assertEquals(4, m.bitFor(m.typeFor(NoiseMode.ANC, null)))
    }

    @Test fun adaptiveNeedsTwoMaskBytes() {
        assertArrayEquals(hex("01 01 00 08"), Payloads.setNoise(11))
    }

    @Test fun basicModelsUseBit0ForAnc() {
        val nord3 = Catalog.model("065014")
        assertEquals(0, nord3.bitFor(nord3.typeFor(NoiseMode.ANC, AncLevel.MAX)))
        assertEquals(1, nord3.bitFor(nord3.typeFor(NoiseMode.OFF, null)))
    }

    @Test fun parsesSplitAndConcatenatedFrames() {
        val a = Frame(Cmd.PRODUCT_ID or Cmd.REPLY, 3, hex("00 14 54 06")).encode()
        val b = Frame(Cmd.NOISE_QUERY or Cmd.REPLY, 4, hex("00 01 01 00 01")).encode()
        val all = byteArrayOf(0x13) + a + b // basura delante
        val p = FrameParser()
        val first = p.feed(all.copyOfRange(0, 7))
        val rest = p.feed(all.copyOfRange(7, all.size))
        assertEquals(0, first.size)
        assertEquals(2, rest.size)
        assertEquals("065414", Parse.productId(rest[0].payload))
        assertEquals(Catalog.BUDS_4, Catalog.model(Parse.productId(rest[0].payload)))
        assertEquals(0x810C, rest[1].command)
    }

    @Test fun parsesNoiseReport() {
        // Buds 4 informan la transparencia en el bit 8 (máscara 00 01)
        val bit = Parse.noiseBit(hex("00 01 01 00 01"))!!
        assertEquals(8, bit)
        assertEquals(NoiseMode.TRANSPARENCY, ModeType.toMode(Catalog.BUDS_4.reportedBits[bit]!!))
        assertEquals(7, Parse.noiseBit(hex("03 01 01 80")))
        assertNull(Parse.noiseBit(hex("00 02")))
    }

    @Test fun parsesBattery() {
        val b = Parse.battery(hex("00 03 01 50 02 E4 03 00"))!!
        assertEquals(80, b.left)
        assertEquals(100, b.right)
        assertEquals(setOf(2), b.charging)
        assertNull(b.case)
    }

    /** Simula unos Buds 4 para probar el driver completo (lo que usa la app). */
    private class FakeBuds4 : Transport {
        val sent = mutableListOf<Frame>()
        private val parser = FrameParser()
        private var out = ByteArray(0)
        var bit = 8 // transparencia, como la informan los Buds 4

        override fun write(bytes: ByteArray) {
            for (f in parser.feed(bytes)) {
                sent += f
                val reply = when (f.command) {
                    Cmd.PRODUCT_ID -> byteArrayOf(0, 0x14, 0x54, 0x06)
                    Cmd.NOISE_QUERY -> byteArrayOf(0, 1, 1, (if (bit < 8) 1 shl bit else 0).toByte(), (if (bit >= 8) 1 shl (bit - 8) else 0).toByte())
                    Cmd.SET_NOISE -> { bit = Integer.numberOfTrailingZeros(f.payload[2].toInt() and 0xFF); byteArrayOf(0) }
                    Cmd.BATTERY -> byteArrayOf(0, 2, 1, 80, 2, 78)
                    else -> null
                } ?: continue
                out += Frame(f.command or Cmd.REPLY, f.seq, reply).encode()
            }
        }

        override fun read(buf: ByteArray, timeoutMs: Long): Int {
            if (out.isEmpty()) { Thread.sleep(minOf(timeoutMs, 5)); return 0 }
            val n = minOf(out.size, buf.size)
            out.copyInto(buf, 0, 0, n)
            out = ByteArray(0)
            return n
        }
    }

    @Test fun headsetDriverRoundTrip() {
        val fake = FakeBuds4()
        val hs = Family.HEYMELODY.create(fake) {}
        val id = hs.identify(null)
        assertEquals("065414", id.id)
        assertEquals("OnePlus Buds 4", id.name)
        assertEquals(Reading(NoiseMode.TRANSPARENCY, null), hs.readMode())
        hs.setMode(NoiseMode.ANC, AncLevel.MAX)
        assertArrayEquals(hex("01 01 10"), fake.sent.last().payload)
        assertEquals(Reading(NoiseMode.ANC, AncLevel.MAX), hs.readMode())
        assertEquals(80, hs.readBattery()!!.left)
        // con el id ya guardado no vuelve a preguntarlo
        val again = FakeBuds4()
        Family.HEYMELODY.create(again) {}.identify("065414")
        assertEquals(0, again.sent.size)
    }
}

package com.diagnosepro.app.elm327

import com.diagnosepro.app.obd.DtcKind
import com.diagnosepro.app.obd.ObdService
import com.diagnosepro.app.obd.Pids
import com.diagnosepro.app.transport.OpenFailure
import com.diagnosepro.app.transport.TransportClosedException
import com.diagnosepro.app.transport.TransportOpenException
import com.diagnosepro.app.transport.WifiTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/** Real TCP socket against a tiny fake Wi-Fi ELM327 (as on 192.168.0.10:35000). */
class WifiTransportTest {

    /** Answers like a clone "v1.5" Wi-Fi adapter on a CAN car; sends data in small TCP pieces. */
    private class FakeWifiElm : AutoCloseable {
        val server = ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
        val host: String get() = server.inetAddress.hostAddress!!
        @Volatile var client: Socket? = null
        private val worker = thread(isDaemon = true) {
            runCatching {
                val s = server.accept(); client = s
                val input = s.getInputStream(); val out = s.getOutputStream()
                val sb = StringBuilder()
                while (true) {
                    val b = input.read(); if (b < 0) break
                    if (b.toChar() != '\r') { sb.append(b.toChar()); continue }
                    val cmd = sb.toString().trim().uppercase(); sb.clear()
                    val ans = when {
                        cmd == "ATZ" -> "\r\rELM327 v1.5"
                        cmd == "ATI" -> "ELM327 v1.5"
                        cmd == "ATDPN" -> "A6"
                        cmd == "ATRV" -> "13.9V"
                        cmd.startsWith("AT") -> "OK"
                        cmd == "0100" -> "410000180000"                      // PIDs 0C, 0D
                        cmd == "010C" -> "410C0FA0"                          // 1000 rpm
                        cmd == "010D" -> "410D32"
                        cmd == "03" -> "7E8044301010300"                     // P0103 (count 1) from 7E8
                        else -> "NO DATA"
                    }
                    for (chunk in ("$ans\r\r>").chunked(5)) { out.write(chunk.toByteArray()); out.flush() }
                }
            }
        }
        override fun close() { runCatching { client?.close() }; runCatching { server.close() } }
    }

    @Test fun fullStackOverTcp() = runBlocking {
        FakeWifiElm().use { fake ->
            val t = WifiTransport(fake.host, fake.server.localPort)
            t.open()
            val elm = Elm327(t)
            val info = elm.initialize()
            assertEquals(ObdProtocol.CAN_11_500, info.protocol)
            assertTrue("v1.5 must be flagged as a likely clone", info.likelyClone)
            assertEquals(13.9, info.voltage!!, 0.001)
            val obd = ObdService(elm)
            assertEquals(setOf(0x0C, 0x0D), obd.readSupportedPids())
            assertEquals(1000.0, obd.readPid(Pids.byKey("rpm")!!)!!, 0.001)
            assertEquals(50.0, obd.readPid(Pids.byKey("speed")!!)!!, 0.001)
            val (codes, err) = obd.readDtcs(DtcKind.STORED)
            assertNull(err); assertEquals("P0103", codes.single().code); assertEquals("7E8", codes.single().ecu)
            t.close()
        }
    }

    @Test fun adapterSwitchedOffMidSession() = runBlocking {
        FakeWifiElm().use { fake ->
            val t = WifiTransport(fake.host, fake.server.localPort)
            t.open()
            val elm = Elm327(t); elm.initialize()
            fake.close() // adapter unplugged
            try {
                repeat(5) { elm.send("010C", 500) }
                fail("expected the link loss to be reported")
            } catch (e: TransportClosedException) { }
        }
    }

    @Test fun nothingListening() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        try { WifiTransport("127.0.0.1", port).open(); fail() } catch (e: TransportOpenException) {
            assertEquals(OpenFailure.SOCKET_FAILED, e.reason)
        }
    }

    @Test fun addressParsing() {
        assertEquals("192.168.0.10" to 35000, WifiTransport.parseAddress("192.168.0.10:35000"))
        assertEquals("192.168.4.1" to 35000, WifiTransport.parseAddress(" 192.168.4.1 "))
        assertNotNull(WifiTransport.parseAddress("obd.local:23"))
        for (bad in listOf("", "1.2.3.4:abc", "1.2.3.4:0", "1.2.3.4:70000", "bad host:1")) assertNull(bad, WifiTransport.parseAddress(bad))
    }
}

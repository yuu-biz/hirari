package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class NetBiosTest {
    @Test
    fun `the request is the RFC 1002 node status request for the name star`() {
        val expected = byteArrayOf(0x12, 0x34, 0x00, 0x10, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0x20) +
            "CK${"A".repeat(30)}".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0x00, 0x00, 0x21, 0x00, 0x01)
        assertContentEquals(expected, nodeStatusRequest(0x1234))
    }

    @Test
    fun `a well formed answer gives its unique file server name`() {
        val packet = response(
            0x1234,
            listOf(Entry("WORKGROUP", 0x00, GROUP), Entry("NAS", 0x00), Entry("NAS", 0x20), Entry("WORKGROUP", 0x1e, GROUP)),
        )
        assertEquals(NodeStatusReply.FileServer("NAS"), parseNodeStatus(packet, packet.size, 0x1234))
    }

    @Test
    fun `a name pointer that points at itself, ahead, into the header or out of the packet is malformed`() {
        val names = listOf(Entry("NAS", 0x20))
        val pointers = mapOf(
            "itself" to byteArrayOf(0xc0.toByte(), 0x0c),
            "ahead" to byteArrayOf(0xc0.toByte(), 0x30),
            "into the header" to byteArrayOf(0xc0.toByte(), 0x00),
            "past the packet" to byteArrayOf(0xc1.toByte(), 0x00),
            "reserved label bits" to byteArrayOf(0x80.toByte(), 0x0c),
        )
        for ((case, pointer) in pointers) {
            val packet = response(0x1234, names, name = pointer)
            assertEquals(NodeStatusReply.Malformed, parseNodeStatus(packet, packet.size, 0x1234), case)
        }
    }

    @Test
    fun `a name is skipped over a pointer only when the pointer goes back to a well formed name`() {
        val name = byteArrayOf(2, 'N'.code.toByte(), 'S'.code.toByte(), 0)
        val packet = ByteArray(12) + name + byteArrayOf(0xc0.toByte(), 12, 9, 9)
        assertEquals(16, skipName(packet, 12, packet.size))
        assertEquals(18, skipName(packet, 16, packet.size))
        val viaPointer = ByteArray(12) + name + byteArrayOf(3, 'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 0xc0.toByte(), 12)
        assertEquals(viaPointer.size, skipName(viaPointer, 16, viaPointer.size))
        val cut = ByteArray(12) + byteArrayOf(1, 'N'.code.toByte(), 0xc0.toByte(), 12)
        assertEquals(null, skipName(cut, 14, cut.size), "the name it points at runs into the pointer")
        val loop = ByteArray(12) + byteArrayOf(0xc0.toByte(), 14, 0xc0.toByte(), 12)
        assertEquals(null, skipName(loop, 12, loop.size), "two pointers at each other")
        assertEquals(null, skipName(packet, 16, 17), "a pointer cut short")
    }

    @Test
    fun `an answer with a question or other than one answer is malformed`() {
        val names = listOf(Entry("NAS", 0x20))
        for ((case, packet) in mapOf(
            "a question" to response(0x1234, names, questions = 1),
            "two answers" to response(0x1234, names, answers = 2),
            "no answer" to response(0x1234, names, answers = 0),
        )) {
            assertEquals(NodeStatusReply.Malformed, parseNodeStatus(packet, packet.size, 0x1234), case)
        }
    }

    @Test
    fun `the statistics after the name table are not read`() {
        val withoutStatistics = response(0x1234, listOf(Entry("NAS", 0x20)), statistics = 0)
        assertEquals(NodeStatusReply.FileServer("NAS"), parseNodeStatus(withoutStatistics, withoutStatistics.size, 0x1234))
        val long = response(0x1234, listOf(Entry("NAS", 0x20)), statistics = 80)
        assertEquals(NodeStatusReply.FileServer("NAS"), parseNodeStatus(long, long.size, 0x1234))
    }


    @Test
    fun `a machine without a unique 0x20 name is not a file server`() {
        val packet = response(0x1234, listOf(Entry("NAS", 0x00), Entry("SHARED", 0x20, GROUP)))
        assertEquals(NodeStatusReply.NoFileServer, parseNodeStatus(packet, packet.size, 0x1234))
    }

    @Test
    fun `a name that is not printable ASCII leaves the row unnamed`() {
        val packet = response(0x1234, listOf(Entry("école", 0x20)))
        assertEquals(NodeStatusReply.FileServer(null), parseNodeStatus(packet, packet.size, 0x1234))
    }

    @Test
    fun `every truncation of an answer is malformed`() {
        val packet = response(0x1234, listOf(Entry("NAS", 0x00), Entry("NAS", 0x20)))
        for (length in 0 until packet.size) {
            assertEquals(NodeStatusReply.Malformed, parseNodeStatus(packet, length, 0x1234), "cut at $length")
        }
    }

    @Test
    fun `a name table longer than its data is malformed`() {
        val packet = response(0x1234, listOf(Entry("NAS", 0x20)), count = 10)
        assertEquals(NodeStatusReply.Malformed, parseNodeStatus(packet, packet.size, 0x1234))
        val short = response(0x1234, listOf(Entry("NAS", 0x20), Entry("NAS", 0x00)), dataLength = 1 + 18)
        assertEquals(NodeStatusReply.Malformed, parseNodeStatus(short, short.size, 0x1234))
    }

    @Test
    fun `an answer to another request, a request, an error and another opcode are malformed`() {
        val names = listOf(Entry("NAS", 0x20))
        val cases = mapOf(
            "other id" to response(0x1235, names),
            "a request" to response(0x1234, names, flags = 0x0400),
            "an error" to response(0x1234, names, flags = 0x8403),
            "a registration" to response(0x1234, names, flags = 0x8400 or (5 shl 11)),
            "no answer" to response(0x1234, names, answers = 0),
            "not NBSTAT" to response(0x1234, names, type = 0x0020),
        )
        for ((case, packet) in cases) assertEquals(NodeStatusReply.Malformed, parseNodeStatus(packet, packet.size, 0x1234), case)
    }

    @Test
    fun `a network knows its broadcast address and its members`() {
        val home = Ipv4Network(ip("192.168.1.20"), 24)
        assertEquals(ip("192.168.1.255"), home.broadcast)
        assertTrue(ip("192.168.1.3") in home)
        assertTrue(ip("192.168.2.3") !in home)
        assertEquals(ip("10.0.0.3"), Ipv4Network(ip("10.0.0.1"), 30).broadcast)
        assertEquals(null, Ipv4Network(ip("10.0.0.1"), 31).broadcast)
        assertTrue(ip("8.8.8.8") in Ipv4Network(ip("10.0.0.1"), 0))
    }

    @Test
    fun `the sender asks at each time of the schedule and reads the answers from the network`() = runBlocking {
        Responder { request -> listOf(response(request.id() + 1, listOf(Entry("WRONG", 0x20))), response(request.id(), listOf(Entry("NAS", 0x20)))) }.use { responder ->
            val opened = mutableListOf<DatagramSocket>()
            val target = responder.target(Ipv4Network(ip("127.0.0.1"), 8)) { DatagramSocket(0, InetAddress.getLoopbackAddress()).also { opened += it } }
            val browser = NodeStatusBrowser(schedule = listOf(0.milliseconds, 100.milliseconds, 300.milliseconds), transactionId = { 0x4242 })
            val hosts = Collections.synchronizedList(mutableListOf<NetBiosHost>())
            val search = launch(Dispatchers.IO) { browser.hosts(listOf(target)).collect { hosts += it } }
            withTimeout(5.seconds) { while (hosts.size < 3) delay(10) }
            search.cancel()
            search.join()
            assertEquals(List(3) { NetBiosHost("NAS", ip("127.0.0.1")) }, hosts.toList())
            val requests = responder.requests.toList()
            assertEquals(3, requests.size)
            requests.forEach { assertContentEquals(nodeStatusRequest(0x4242), it.bytes) }
            assertTrue(requests[1].at - requests[0].at >= 90, "second request ${requests[1].at - requests[0].at} ms after the first")
            assertTrue(requests[2].at - requests[1].at >= 190, "third request ${requests[2].at - requests[1].at} ms after the second")
            assertTrue(opened.single().isClosed)
        }
    }

    @Test
    fun `answers from outside the network sent to are dropped`() = runBlocking {
        Responder { request -> listOf(response(request.id(), listOf(Entry("NAS", 0x20)))) }.use { responder ->
            val target = responder.target(Ipv4Network(ip("10.0.0.1"), 8)) { DatagramSocket(0, InetAddress.getLoopbackAddress()) }
            val browser = NodeStatusBrowser(schedule = listOf(0.milliseconds), transactionId = { 7 })
            val hosts = mutableListOf<NetBiosHost>()
            val search = launch(Dispatchers.IO) { browser.hosts(listOf(target)).collect { hosts += it } }
            withTimeout(5.seconds) { while (responder.requests.isEmpty()) delay(10) }
            delay(200)
            search.cancel()
            search.join()
            assertEquals(emptyList(), hosts)
        }
    }

    @Test
    fun `cancelling closes the socket and a reply after it is not read`() = runBlocking {
        Responder { emptyList() }.use { responder ->
            val opened = mutableListOf<DatagramSocket>()
            val target = responder.target(Ipv4Network(ip("127.0.0.1"), 8)) { DatagramSocket(0, InetAddress.getLoopbackAddress()).also { opened += it } }
            val browser = NodeStatusBrowser(schedule = listOf(0.milliseconds), transactionId = { 9 })
            val hosts = mutableListOf<NetBiosHost>()
            val search = launch(Dispatchers.IO) { browser.hosts(listOf(target)).toList(hosts) }
            withTimeout(5.seconds) { while (responder.requests.isEmpty()) delay(10) }
            search.cancel()
            withTimeout(2.seconds) { search.join() }
            val socket = opened.single()
            assertTrue(socket.isClosed)
            responder.answer(response(9, listOf(Entry("LATE", 0x20))), responder.requests.single().from)
            delay(100)
            assertEquals(emptyList(), hosts)
        }
    }

    @Test
    fun `a target whose socket does not open is skipped`() = runBlocking {
        Responder { request -> listOf(response(request.id(), listOf(Entry("NAS", 0x20)))) }.use { responder ->
            val broken = responder.target(Ipv4Network(ip("127.0.0.1"), 8)) { throw SocketException("no network") }
            val working = responder.target(Ipv4Network(ip("127.0.0.1"), 8)) { DatagramSocket(0, InetAddress.getLoopbackAddress()) }
            val browser = NodeStatusBrowser(schedule = listOf(0.milliseconds), transactionId = { 3 })
            val hosts = Collections.synchronizedList(mutableListOf<NetBiosHost>())
            val search = launch(Dispatchers.IO) { browser.hosts(listOf(broken, working)).collect { hosts += it } }
            withTimeout(5.seconds) { while (hosts.isEmpty()) delay(10) }
            search.cancel()
            assertEquals(NetBiosHost("NAS", ip("127.0.0.1")), hosts.first())
        }
    }
}

private const val GROUP = 0x8400

private data class Entry(val name: String, val suffix: Int, val flags: Int = 0x0400)

private fun encodedStar(): ByteArray = byteArrayOf(0x20) + "CK${"A".repeat(30)}".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)

/** A node status response of RFC 1002 section 4.2.18, with each field open to being made wrong. */
private fun response(
    id: Int,
    entries: List<Entry>,
    flags: Int = 0x8400,
    answers: Int = 1,
    questions: Int = 0,
    statistics: Int = 46,
    name: ByteArray = encodedStar(),
    type: Int = 0x0021,
    count: Int = entries.size,
    dataLength: Int? = null,
): ByteArray {
    val table = ByteArrayOutputStream()
    DataOutputStream(table).run {
        writeByte(count)
        for (entry in entries) {
            write(entry.name.toByteArray(Charsets.ISO_8859_1).copyOf(15).map { if (it == 0.toByte()) ' '.code.toByte() else it }.toByteArray())
            writeByte(entry.suffix)
            writeShort(entry.flags)
        }
        write(ByteArray(statistics))
    }
    val data = table.toByteArray()
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).run {
        writeShort(id)
        writeShort(flags)
        writeShort(questions)
        writeShort(answers)
        writeShort(0)
        writeShort(0)
        write(name)
        writeShort(type)
        writeShort(0x0001)
        writeInt(0)
        writeShort(dataLength ?: data.size)
        write(data)
    }
    return bytes.toByteArray()
}

private fun ip(dotted: String): Inet4Address = InetAddress.getByName(dotted) as Inet4Address

private class Received(val bytes: ByteArray, val from: InetSocketAddress, val at: Long) {
    fun id(): Int = (bytes[0].toInt() and 0xff shl 8) or (bytes[1].toInt() and 0xff)
}

/** A NetBIOS name service on loopback that answers each request with [answers]. */
private class Responder(private val answers: (Received) -> List<ByteArray>) : AutoCloseable {
    private val socket = DatagramSocket(0, InetAddress.getLoopbackAddress())
    val requests: MutableList<Received> = Collections.synchronizedList(mutableListOf())

    private val loop = thread(isDaemon = true) {
        val buffer = ByteArray(2048)
        while (true) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketException) {
                return@thread
            }
            val request = Received(buffer.copyOf(packet.length), packet.socketAddress as InetSocketAddress, System.nanoTime() / 1_000_000)
            requests += request
            answers(request).forEach { answer(it, request.from) }
        }
    }

    fun answer(bytes: ByteArray, to: InetSocketAddress) = socket.send(DatagramPacket(bytes, bytes.size, to))

    fun target(network: Ipv4Network, open: () -> DatagramSocket) =
        NetBiosTarget(InetSocketAddress(InetAddress.getLoopbackAddress(), socket.localPort), network, open)

    override fun close() {
        socket.close()
        loop.join(1000)
    }
}

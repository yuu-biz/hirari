package io.github.yuubiz.hirari.core.source.smb

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** An IPv4 network of the device: one of its addresses and the length of the network prefix. */
data class Ipv4Network(val address: Inet4Address, val prefixLength: Int) {
    init {
        require(prefixLength in 0..32) { "prefix length $prefixLength" }
    }

    private val mask: Int get() = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)

    operator fun contains(other: Inet4Address): Boolean = other.bits() and mask == address.bits() and mask

    /** The directed broadcast address; a /31 or /32 network has none. */
    val broadcast: Inet4Address? get() = if (prefixLength > 30) null else ipv4(address.bits() or mask.inv())
}

internal fun Inet4Address.bits(): Int = address.fold(0) { bits, byte -> bits shl 8 or (byte.toInt() and 0xff) }

internal fun ipv4(bits: Int): Inet4Address =
    Inet4Address.getByAddress(ByteArray(4) { (bits ushr (24 - it * 8)).toByte() }) as Inet4Address

/**
 * Where one node status request goes: [destination] is the broadcast address and port of [network], and answers
 * from outside [network] are dropped. [openSocket] gives a socket that sends on that network; the sender closes it.
 */
class NetBiosTarget(val destination: InetSocketAddress, val network: Ipv4Network, val openSocket: () -> DatagramSocket)

/** A machine that answered with a file server name. [name] is null when its name is not printable ASCII. */
data class NetBiosHost(val name: String?, val address: Inet4Address)

fun interface NetBiosBrowser {
    /** The machines that answer on [targets], until the collector stops; each may come more than once. */
    fun hosts(targets: List<NetBiosTarget>): Flow<NetBiosHost>
}

/** What one datagram is, as a node status response of RFC 1002 section 4.2.18. */
internal sealed interface NodeStatusReply {
    data object Malformed : NodeStatusReply

    /** A well formed answer without a unique name of suffix `0x20`. */
    data object NoFileServer : NodeStatusReply

    data class FileServer(val name: String?) : NodeStatusReply
}

const val NETBIOS_NAME_PORT = 137

/** RFC 1002 section 4.2.17 with the B flag, for the name `*` padded with NULs. */
internal fun nodeStatusRequest(transactionId: Int): ByteArray {
    val packet = ByteArray(50)
    packet.putShort(0, transactionId)
    packet.putShort(2, FLAG_BROADCAST)
    packet.putShort(4, 1)
    packet[12] = ENCODED_NAME_LENGTH.toByte()
    val name = ByteArray(NETBIOS_NAME_LENGTH).also { it[0] = '*'.code.toByte() }
    name.forEachIndexed { i, byte ->
        packet[13 + i * 2] = ('A'.code + (byte.toInt() shr 4 and 0xf)).toByte()
        packet[14 + i * 2] = ('A'.code + (byte.toInt() and 0xf)).toByte()
    }
    packet.putShort(46, RR_TYPE_NBSTAT)
    packet.putShort(48, RR_CLASS_IN)
    return packet
}

/**
 * Reads the first [length] bytes of [packet] as the answer to the request [transactionId]: no question and one answer.
 * Every field is checked against the bytes that came, so a short or inconsistent datagram is [NodeStatusReply.Malformed].
 * The statistics after the name table are not read, since responders differ in them.
 */
internal fun parseNodeStatus(packet: ByteArray, length: Int, transactionId: Int): NodeStatusReply {
    val end = length.coerceAtMost(packet.size)
    if (end < HEADER_LENGTH) return NodeStatusReply.Malformed
    val flags = packet.short(2)
    val valid = packet.short(0) == transactionId &&
        flags and FLAG_RESPONSE != 0 &&
        flags shr 11 and 0xf == 0 &&
        flags and 0xf == 0 &&
        packet.short(4) == 0 &&
        packet.short(6) == 1
    if (!valid) return NodeStatusReply.Malformed
    var at = skipName(packet, HEADER_LENGTH, end) ?: return NodeStatusReply.Malformed
    if (at + 10 > end) return NodeStatusReply.Malformed
    if (packet.short(at) != RR_TYPE_NBSTAT || packet.short(at + 2) != RR_CLASS_IN) return NodeStatusReply.Malformed
    val dataLength = packet.short(at + 8)
    at += 10
    if (dataLength < 1 || at + dataLength > end) return NodeStatusReply.Malformed
    val names = packet[at].toInt() and 0xff
    if (1 + names * NAME_ENTRY_LENGTH > dataLength) return NodeStatusReply.Malformed
    for (i in 0 until names) {
        val entry = at + 1 + i * NAME_ENTRY_LENGTH
        val suffix = packet[entry + NETBIOS_NAME_LENGTH - 1].toInt() and 0xff
        val group = packet.short(entry + NETBIOS_NAME_LENGTH) and NAME_FLAG_GROUP != 0
        if (suffix == FILE_SERVER_SUFFIX && !group) {
            return NodeStatusReply.FileServer(printable(packet.copyOfRange(entry, entry + NETBIOS_NAME_LENGTH - 1)))
        }
    }
    return NodeStatusReply.NoFileServer
}

/**
 * The offset after the name at [start] in the first [end] bytes. A name ends in a zero length or a two-byte pointer.
 * A pointer is followed only backwards, to a position after the header and before the pointer itself, and the name it
 * reaches must end before that pointer, so a pointer to itself, ahead or into the header, and any loop, is null.
 */
internal fun skipName(packet: ByteArray, start: Int, end: Int): Int? {
    var at = start
    var limit = end
    var after: Int? = null
    while (at < limit) {
        val label = packet[at].toInt() and 0xff
        when {
            label == 0 -> return after ?: (at + 1)
            label and 0xc0 == 0xc0 -> {
                if (at + 2 > limit) return null
                val target = (label and 0x3f shl 8) or (packet[at + 1].toInt() and 0xff)
                if (target < HEADER_LENGTH || target >= at) return null
                if (after == null) after = at + 2
                limit = at
                at = target
            }
            label and 0xc0 != 0 -> return null
            else -> at += 1 + label
        }
    }
    return null
}

private fun printable(name: ByteArray): String? {
    val trimmed = name.dropLastWhile { it == ' '.code.toByte() || it == 0.toByte() }
    if (trimmed.isEmpty() || trimmed.any { it < 0x20 || it > 0x7e }) return null
    return String(trimmed.toByteArray(), Charsets.US_ASCII)
}

/**
 * The NBSTAT search of docs/spec.md section 4.5: one socket per target, the request sent to every target at each time
 * of [schedule], and the answers read until the collector stops, which closes the sockets.
 */
class NodeStatusBrowser(
    private val schedule: List<Duration> = NODE_STATUS_SCHEDULE,
    private val transactionId: () -> Int = { Random.nextInt(0x10000) },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : NetBiosBrowser {
    override fun hosts(targets: List<NetBiosTarget>): Flow<NetBiosHost> = channelFlow {
        val id = transactionId() and 0xffff
        val request = nodeStatusRequest(id)
        val open = mutableListOf<Pair<NetBiosTarget, DatagramSocket>>()
        try {
            for (target in targets) {
                val socket = try {
                    target.openSocket()
                } catch (_: IOException) {
                    continue
                } catch (_: SecurityException) {
                    continue
                }
                open += target to socket
                launch { receive(socket, target.network, id) }
            }
            var sent = Duration.ZERO
            for (at in schedule) {
                delay(at - sent)
                sent = at
                for ((target, socket) in open) {
                    try {
                        socket.send(DatagramPacket(request, request.size, target.destination))
                    } catch (_: IOException) {
                    }
                }
            }
            awaitCancellation()
        } finally {
            open.forEach { (_, socket) -> socket.close() }
        }
    }.flowOn(io)

    /** Blocks in [DatagramSocket.receive] until the socket closes, which is how cancellation reaches it. */
    private suspend fun ProducerScope<NetBiosHost>.receive(socket: DatagramSocket, network: Ipv4Network, id: Int) {
        val buffer = ByteArray(RECEIVE_BUFFER)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: IOException) {
                return
            }
            val from = packet.address as? Inet4Address ?: continue
            if (from !in network) continue
            val reply = parseNodeStatus(buffer, packet.length, id)
            if (reply is NodeStatusReply.FileServer) send(NetBiosHost(reply.name, from))
        }
    }
}

val NODE_STATUS_SCHEDULE = listOf(0.seconds, 1.seconds, 3.seconds)

private fun ByteArray.short(at: Int): Int = (this[at].toInt() and 0xff shl 8) or (this[at + 1].toInt() and 0xff)

private fun ByteArray.putShort(at: Int, value: Int) {
    this[at] = (value shr 8).toByte()
    this[at + 1] = value.toByte()
}

private const val HEADER_LENGTH = 12
private const val NETBIOS_NAME_LENGTH = 16
private const val ENCODED_NAME_LENGTH = 32
private const val NAME_ENTRY_LENGTH = 18
private const val FLAG_RESPONSE = 0x8000
private const val FLAG_BROADCAST = 0x0010
private const val NAME_FLAG_GROUP = 0x8000
private const val RR_TYPE_NBSTAT = 0x0021
private const val RR_CLASS_IN = 0x0001
private const val FILE_SERVER_SUFFIX = 0x20
private const val RECEIVE_BUFFER = 8192

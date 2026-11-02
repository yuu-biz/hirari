package io.github.yuubiz.hirari.core.source.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.SocketFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The timeouts of docs/spec.md section 6.5. */
data class SmbTimeouts(
    /** From name resolution to the connected share. */
    val connect: Duration = 5.seconds,
    /** One request on an open connection, a read among them. */
    val request: Duration = 15.seconds,
)

/**
 * Connects with smbj. Each connection gets its own [SMBClient], so a broken one is never handed out again.
 *
 * Encryption follows the share: the first attempt does not encrypt, and a password login whose share refuses the
 * unencrypted tree connect tries once more with SMB 3 encryption (docs/spec.md section 4.5).
 */
internal class SmbjConnector(
    private val address: SmbAddress,
    private val login: SmbLogin,
    private val timeouts: SmbTimeouts,
) : ShareConnector {
    private val plain = config(encrypt = false)
    private val encrypting = config(encrypt = true)

    private fun config(encrypt: Boolean) = SmbConfig.builder()
        .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
        .withTimeout(timeouts.request.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .withSocketFactory(ConnectTimeoutSocketFactory(timeouts.connect.inWholeMilliseconds.toInt()))
        .withEncryptData(encrypt)
        .build()

    override fun connect(): ShareConnection {
        val clients = CopyOnWriteArrayList<SMBClient>()
        val attempt = connectThreads.submit<ShareConnection> {
            try {
                open(SMBClient(plain).also(clients::add))
            } catch (e: TreeConnectDenied) {
                if (login !is SmbLogin.Password) throw e.failure
                try {
                    open(SMBClient(encrypting).also(clients::add))
                } catch (e: TreeConnectDenied) {
                    throw e.failure
                }
            }
        }
        try {
            return attempt.get(timeouts.connect.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            attempt.cancel(true)
            clients.forEach(SMBClient::close)
            throw SmbConnectException(SmbConnectFailure.Unreachable, "no answer within ${timeouts.connect}")
        } catch (e: ExecutionException) {
            clients.forEach(SMBClient::close)
            throw e.cause ?: e
        }
    }

    private fun open(client: SMBClient): ShareConnection {
        val connection = try {
            client.connect(address.host, address.port)
        } catch (e: Exception) {
            throw SmbConnectException(classifyConnect(e), e.javaClass.simpleName)
        }
        try {
            val session = try {
                connection.authenticate(authentication())
            } catch (e: SMBApiException) {
                throw SmbConnectException(SmbConnectFailure.AuthenticationFailed, e.status.name)
            }
            val share = try {
                session.connectShare(address.share)
            } catch (e: SMBApiException) {
                throw when (e.status) {
                    NtStatus.STATUS_BAD_NETWORK_NAME, NtStatus.STATUS_BAD_NETWORK_PATH ->
                        SmbConnectException(SmbConnectFailure.ShareNotFound, e.status.name)
                    NtStatus.STATUS_ACCESS_DENIED -> TreeConnectDenied(SmbConnectException(SmbConnectFailure.AuthenticationFailed, e.status.name))
                    else -> SmbConnectException(SmbConnectFailure.ProtocolError, e.status.name)
                }
            }
            if (share !is DiskShare) {
                share.close()
                throw SmbConnectException(SmbConnectFailure.ShareNotFound, "not a disk share")
            }
            return SmbjShare(client, connection, share)
        } catch (e: Exception) {
            connection.close(true)
            client.close()
            throw when (e) {
                is SmbConnectException, is TreeConnectDenied -> e
                else -> SmbConnectException(SmbConnectFailure.ProtocolError, e.javaClass.simpleName)
            }
        }
    }

    /** smbj 0.15.0 cannot set up an anonymous session with Samba over SMB 3.1.1, so no login means guest. */
    private fun authentication(): AuthenticationContext = when (login) {
        SmbLogin.Guest -> AuthenticationContext.guest()
        is SmbLogin.Password -> AuthenticationContext(login.user, login.password, address.domain)
    }

    private class TreeConnectDenied(val failure: SmbConnectException) : Exception(null, null, false, false)

    private companion object {
        val connectThreads = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "smb-connect").apply { isDaemon = true }
        }
    }
}

private fun classifyConnect(e: Exception): SmbConnectFailure {
    var cause: Throwable? = e
    while (cause != null) {
        when (cause) {
            is UnknownHostException -> return SmbConnectFailure.NameNotResolved
            is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> return SmbConnectFailure.Unreachable
        }
        cause = cause.cause
    }
    return SmbConnectFailure.ProtocolError
}

/** smbj creates its socket with [createSocket] (host, port), which would connect without a timeout. */
private class ConnectTimeoutSocketFactory(private val timeoutMillis: Int) : SocketFactory() {
    override fun createSocket(): Socket = Socket()

    override fun createSocket(host: String, port: Int): Socket =
        Socket().apply {
            try {
                connect(InetSocketAddress(host, port), timeoutMillis)
            } catch (e: Exception) {
                close()
                throw e
            }
        }

    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress, localPort: Int): Socket =
        throw UnsupportedOperationException()

    override fun createSocket(host: java.net.InetAddress, port: Int): Socket = createSocket(host.hostAddress!!, port)

    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress, localPort: Int): Socket =
        throw UnsupportedOperationException()
}

private class SmbjShare(
    private val client: SMBClient,
    private val connection: Connection,
    private val share: DiskShare,
) : ShareConnection {
    override fun list(path: String): List<SmbEntry> = request {
        val parent = path.trim('/')
        share.list(parent.smbPath()).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            SmbEntry(
                name = name,
                path = if (parent.isEmpty()) name else "$parent/$name",
                isDirectory = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                size = info.endOfFile,
                modified = java.time.Instant.ofEpochMilli(info.lastWriteTime.toEpochMillis()),
            )
        }
    }

    override fun open(path: String): RemoteFile = request {
        val file = share.openFile(
            path.trim('/').smbPath(),
            EnumSet.of(AccessMask.GENERIC_READ),
            null,
            SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN,
            null,
        )
        try {
            SmbjFile(file, file.getFileInformation(FileStandardInformation::class.java).endOfFile)
        } catch (e: Exception) {
            file.closeSilently()
            throw e
        }
    }

    override fun closeNow() {
        runCatching { connection.close(true) }
        client.close()
    }
}

private class SmbjFile(private val file: File, override val size: Long) : RemoteFile {
    override fun read(offset: Long, dst: ByteArray, dstOffset: Int, length: Int): Int =
        request { file.read(dst, offset, dstOffset, length) }

    override fun closeNow() = file.closeSilently()
}

private fun String.smbPath(): String = replace('/', '\\')

/**
 * Turns smbj's exceptions into the ones [ShareConnection] promises. smbj's messages can carry the user name, so
 * only the status or the exception's class goes on (docs/spec.md section 6.6).
 */
private inline fun <T> request(block: () -> T): T =
    try {
        block()
    } catch (e: SMBApiException) {
        throw when (e.status) {
            NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND, NtStatus.STATUS_NOT_FOUND,
            NtStatus.STATUS_FILE_DELETED,
            -> FileNotFoundException(e.status.name)
            NtStatus.STATUS_NETWORK_NAME_DELETED, NtStatus.STATUS_USER_SESSION_DELETED, NtStatus.STATUS_NETWORK_SESSION_EXPIRED,
            -> ConnectionLostException("SMB session ended: ${e.status.name}")
            else -> SmbRequestException(e.status.name)
        }
    } catch (e: Exception) {
        throw ConnectionLostException("SMB connection failed: ${e.javaClass.simpleName}")
    }

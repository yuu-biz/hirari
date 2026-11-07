package io.github.yuubiz.hirari.core.source.smb

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.transport.TransportException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import io.github.yuubiz.hirari.core.model.NaturalOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** SRVSVC information level 1, without passwords or paths (section 4.5). */
data class DiscoveredShare(val name: String, val type: Int, val remark: String? = null)

enum class ShareDiscoveryFailure { Unreachable, AuthenticationFailed, Unsupported, NoLocalNetwork, PasswordUnreadable, Empty }

sealed interface ShareDiscoveryResult {
    data class Listed(val shares: List<DiscoveredShare>) : ShareDiscoveryResult
    data class Failed(val reason: ShareDiscoveryFailure) : ShareDiscoveryResult
}

/** Each call owns its connection and wipes the login, including on cancellation. */
fun interface SmbShareLister {
    suspend fun list(address: SmbAddress, login: SmbLogin): ShareDiscoveryResult
}

/** STYPE_DISKTREE is zero; the low byte is the kind and the high bit marks special shares. */
internal fun diskShares(shares: List<DiscoveredShare>): List<DiscoveredShare> = shares
    .filter { it.type and 0xff == 0 && it.type and Int.MIN_VALUE == 0 }
    .sortedWith(compareBy(NaturalOrder) { it.name })

class SrvsvcShareLister(private val timeouts: SmbTimeouts = SmbTimeouts()) : SmbShareLister {
    override suspend fun list(address: SmbAddress, login: SmbLogin): ShareDiscoveryResult = try {
        runInterruptible(Dispatchers.IO) {
            val config = SmbConfig.builder()
                .withDialects(SMB2Dialect.SMB_3_1_1, SMB2Dialect.SMB_3_0_2, SMB2Dialect.SMB_3_0, SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2)
                .withTimeout(timeouts.request.inWholeMilliseconds, TimeUnit.MILLISECONDS)
                .withSocketFactory(ConnectTimeoutSocketFactory(timeouts.connect.inWholeMilliseconds.toInt()))
                .build()
            val client = SMBClient(config)
            val owned = DiscoveryConnection(client)
            try {
                val transport = bounded(timeouts.connect.inWholeMilliseconds) {
                    val connection = client.connect(address.host, address.port)
                    owned.accept(connection)
                    val context = when (login) {
                        SmbLogin.Guest -> AuthenticationContext.guest()
                        is SmbLogin.Password -> AuthenticationContext(login.user, login.password, address.domain)
                    }
                    val session = try {
                        connection.authenticate(context)
                    } catch (_: SMBApiException) {
                        throw DiscoveryAuthenticationException()
                    } finally {
                        context.password?.fill('\u0000')
                    }
                    try {
                        SMBTransportFactories.SRVSVC.getTransport(session)
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: Exception) {
                        throw DiscoveryRpcException(rpcFailure(e))
                    } catch (_: NoClassDefFoundError) {
                        throw DiscoveryRpcException(ShareDiscoveryFailure.Unsupported)
                    }
                }
                try {
                    val shares = bounded(timeouts.request.inWholeMilliseconds) {
                        ServerService(transport).shares1.map { DiscoveredShare(it.netName, it.type, it.remark) }
                    }
                    val offered = diskShares(shares)
                    if (offered.isEmpty()) ShareDiscoveryResult.Failed(ShareDiscoveryFailure.Empty)
                    else ShareDiscoveryResult.Listed(offered)
                } catch (_: TimeoutException) {
                    ShareDiscoveryResult.Failed(ShareDiscoveryFailure.Unreachable)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    // RPC messages can contain user-controlled data. Neither log nor retain the cause.
                    ShareDiscoveryResult.Failed(rpcFailure(e))
                } catch (_: NoClassDefFoundError) {
                    // A malformed reply makes dcerpc throw java.rmi.UnmarshalException, which Android lacks.
                    ShareDiscoveryResult.Failed(ShareDiscoveryFailure.Unsupported)
                }
            } catch (_: DiscoveryAuthenticationException) {
                ShareDiscoveryResult.Failed(ShareDiscoveryFailure.AuthenticationFailed)
            } catch (e: DiscoveryRpcException) {
                ShareDiscoveryResult.Failed(e.reason)
            } catch (e: InterruptedException) {
                throw e
            } catch (_: Exception) {
                ShareDiscoveryResult.Failed(ShareDiscoveryFailure.Unreachable)
            } finally {
                owned.close()
            }
        }
    } finally {
        (login as? SmbLogin.Password)?.wipe()
    }
}

private class DiscoveryAuthenticationException : Exception(null, null, false, false)
private class DiscoveryRpcException(val reason: ShareDiscoveryFailure) : Exception(null, null, false, false)

internal fun rpcFailure(error: Exception): ShareDiscoveryFailure =
    if (generateSequence<Throwable>(error) { it.cause }.take(8).any {
        it is SocketException || it is SocketTimeoutException || it is TimeoutException || it is TransportException
    }) ShareDiscoveryFailure.Unreachable else ShareDiscoveryFailure.Unsupported

/** A cancelled DNS/connect may still finish later. Never let that late connection escape cleanup. */
private class DiscoveryConnection(private val client: SMBClient) {
    private var closed = false
    private var connection: Connection? = null

    @Synchronized
    fun accept(value: Connection) {
        if (closed) {
            runCatching { value.close(true) }
            throw InterruptedException()
        }
        connection = value
    }

    fun close() {
        val connection = synchronized(this) {
            closed = true
            this.connection.also { this.connection = null }
        }
        runCatching { connection?.close(true) }
        runCatching { client.close() }
    }
}

private val discoveryThreads = Executors.newCachedThreadPool { task ->
    Thread(task, "smb-share-discovery").apply { isDaemon = true }
}

private fun <T> bounded(milliseconds: Long, action: () -> T): T {
    val future: Future<T> = discoveryThreads.submit<T> { action() }
    try {
        return future.get(milliseconds, TimeUnit.MILLISECONDS)
    } catch (e: ExecutionException) {
        throw e.cause ?: e
    } finally {
        future.cancel(true)
    }
}

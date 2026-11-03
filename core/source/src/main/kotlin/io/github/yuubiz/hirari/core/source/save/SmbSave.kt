package io.github.yuubiz.hirari.core.source.save

import io.github.yuubiz.hirari.core.source.smb.ConnectionLostException
import io.github.yuubiz.hirari.core.source.smb.SMB_READ_BLOCK
import io.github.yuubiz.hirari.core.source.smb.SmbByteSource
import io.github.yuubiz.hirari.core.source.smb.SmbConnectException
import io.github.yuubiz.hirari.core.source.smb.SmbConnectFailure
import io.github.yuubiz.hirari.core.source.smb.SmbConnectionState
import io.github.yuubiz.hirari.core.source.smb.SmbRequestException
import io.github.yuubiz.hirari.core.source.smb.SmbServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.seconds

/** The reconnects a save waits out in a row without a block arriving in between. */
const val SAVE_MAX_RECOVERIES = 3

/** How long a save waits for the server to connect again before it gives up (docs/spec.md section 4.5). */
val SAVE_RECONNECT_WAIT = 30.seconds

/** The deepest folder a save descends into, so a share that loops on itself ends. */
const val SAVE_MAX_DEPTH = 64

/**
 * Copies the file or folder at [path] of the share to [destination] (docs/spec.md section 4.5, 端末に保存). [path] is
 * counted from the share root. Reads through this server, so the copy shares the connection and the request permits
 * with the listings and the readers (section 8.3). Runs on the I/O dispatcher and, when the caller is cancelled, stops
 * at the next block or before renaming the last, after deleting the file it was writing.
 *
 * Each file is written as `名前.part` and renamed when whole. The item itself takes the first free name of `名前` and
 * `名前 (1)` to `名前 (9)`. A folder is copied with everything in it. [onProgress] gets a value after each block.
 */
suspend fun SmbServer.saveTo(
    path: String,
    directory: Boolean,
    destination: SaveFolder,
    onProgress: (SaveProgress) -> Unit = {},
): SaveOutcome = withContext(Dispatchers.IO) {
    try {
        SmbSaveRun(this@saveTo, destination, onProgress).save(path, directory)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SecurityException) {
        SaveOutcome.Failed(SaveFailure.NoPermission)
    } catch (e: IOException) {
        SaveOutcome.Failed(e.toFailure())
    }
}

private fun IOException.toFailure(): SaveFailure = when (this) {
    is NoRoomException -> SaveFailure.NoRoom
    is NamesTakenException -> SaveFailure.NamesTaken
    is CannotRenameException -> SaveFailure.NoPermission
    is ConnectionLostException -> SaveFailure.ConnectionLost
    is SmbConnectException -> when (failure) {
        SmbConnectFailure.NameNotResolved, SmbConnectFailure.Unreachable -> SaveFailure.Unreachable
        SmbConnectFailure.AuthenticationFailed -> SaveFailure.AuthenticationFailed
        SmbConnectFailure.ShareNotFound -> SaveFailure.NotFound
        SmbConnectFailure.ProtocolError -> SaveFailure.Refused
    }
    is FileNotFoundException -> SaveFailure.NotFound
    is SmbRequestException -> SaveFailure.Refused
    is SaveDestinationException -> SaveFailure.WriteFailed
    else -> SaveFailure.Refused
}

private sealed interface Node {
    val name: String
}

private class FileNode(override val name: String, val path: String, val size: Long) : Node

private class DirNode(override val name: String, val children: List<Node>) : Node

private class SmbSaveRun(
    private val server: SmbServer,
    private val destination: SaveFolder,
    private val onProgress: (SaveProgress) -> Unit,
) {
    private var bytesDone = 0L
    private var bytesTotal = 0L
    private var filesDone = 0
    private var filesTotal = 0
    private var current = ""
    private var written = 0L

    suspend fun save(path: String, directory: Boolean): SaveOutcome {
        val name = path.substringAfterLast('/')
        val node = if (directory) walk(path, name, 0) else FileNode(name, path, size = 0)
        count(node)
        report()
        val saved = when (node) {
            is FileNode -> copyFile(destination, node, resolve = true)
            is DirNode -> copyFolder(destination, node, resolve = true)
        }
        return SaveOutcome.Saved(saved, filesTotal, written)
    }

    private suspend fun walk(path: String, name: String, depth: Int): DirNode {
        if (depth > SAVE_MAX_DEPTH) throw SmbRequestException("folders nested too deep")
        currentCoroutineContext().ensureActive()
        val children = server.list(path).sortedBy { it.name }.map {
            if (it.isDirectory) walk(it.path, it.name, depth + 1) else FileNode(it.name, it.path, it.size)
        }
        return DirNode(name, children)
    }

    private fun count(node: Node) {
        when (node) {
            is FileNode -> {
                filesTotal++
                bytesTotal += node.size
            }
            is DirNode -> node.children.forEach(::count)
        }
    }

    private fun report() = onProgress(SaveProgress(bytesDone, bytesTotal, current, filesDone, filesTotal))

    private suspend fun copyFolder(into: SaveFolder, node: DirNode, resolve: Boolean): String {
        val name = if (resolve) freeName(node.name, onDestination { into.names() }) else node.name
        val folder = onDestination { into.createFolder(name) }
        for (child in node.children) {
            when (child) {
                is FileNode -> copyFile(folder, child, resolve = false)
                is DirNode -> copyFolder(folder, child, resolve = false)
            }
        }
        return name
    }

    private suspend fun copyFile(into: SaveFolder, node: FileNode, resolve: Boolean): String {
        current = node.name
        val source = server.open(node.path)
        try {
            bytesTotal += source.size - node.size
            val part = onDestination {
                val wanted = if (resolve) freeName(node.name, into.names()) else node.name
                (wanted + SAVE_PART_SUFFIX).also { into.delete(it) }
            }
            val file = onDestination { into.create(part) }
            try {
                onDestination { file.freeBytes }?.let { if (it < source.size) throw NoRoomException() }
                val buffer = ByteBuffer.allocate(SMB_READ_BLOCK)
                var offset = 0L
                while (offset < source.size) {
                    currentCoroutineContext().ensureActive()
                    buffer.clear()
                    val count = readBlock(source, offset, buffer)
                    onDestination { file.write(buffer.array(), count) }
                    offset += count
                    bytesDone += count
                    written += count
                    report()
                }
                currentCoroutineContext().ensureActive()
                val final = onDestination { if (resolve) freeName(node.name, into.names()) else node.name }
                onDestination { file.commit(final) }
                filesDone++
                report()
                return final
            } catch (t: Throwable) {
                withContext(NonCancellable) { runCatching { file.discard() } }
                throw t
            }
        } finally {
            source.close()
        }
    }

    private suspend fun readBlock(source: SmbByteSource, offset: Long, buffer: ByteBuffer): Int {
        var recoveries = 0
        while (true) {
            try {
                val count = source.read(offset, buffer)
                if (count <= 0) throw SmbRequestException("the file ended before its size")
                return count
            } catch (e: ConnectionLostException) {
                if (++recoveries > SAVE_MAX_RECOVERIES || !reconnected()) throw e
            }
        }
    }

    /** Starts the server's reconnects over when they all failed, and waits until one connects or all fail. */
    private suspend fun reconnected(): Boolean {
        server.retry()
        val state = withTimeoutOrNull(SAVE_RECONNECT_WAIT) {
            server.state.first { it is SmbConnectionState.Connected || it is SmbConnectionState.Lost }
        }
        return state is SmbConnectionState.Connected
    }

    /** Runs a call on the destination, turning its failures into the types the outcome knows. */
    private inline fun <T> onDestination(block: () -> T): T = try {
        block()
    } catch (e: NoRoomException) {
        throw e
    } catch (e: NamesTakenException) {
        throw e
    } catch (e: CannotRenameException) {
        throw e
    } catch (e: SaveDestinationException) {
        throw e
    } catch (e: IOException) {
        throw SaveDestinationException("the destination failed", e)
    }
}

package io.github.yuubiz.hirari.core.archive

import android.os.ParcelFileDescriptor
import java.io.IOException

/** libarchive could not read the archive. The message is libarchive's `archive_error_string`. */
class ArchiveException(message: String) : IOException(message)

/** The JNI binding to libarchive, built read-only with ZIP as the only format. */
object Libarchive {
    init {
        System.loadLibrary("hirari_archive")
    }

    /**
     * Pathnames of the entries of the ZIP in [fd], in archive order, as the bytes libarchive returns.
     * Names without the UTF-8 flag come back unchanged. libarchive normalizes a name to NFC when the
     * entry has the UTF-8 flag or an Info-ZIP Unicode Path field. [fd] stays open.
     *
     * @throws ArchiveException when libarchive cannot open the archive, read a header, or give a name.
     */
    fun entryNames(fd: ParcelFileDescriptor): List<ByteArray> =
        ArrayList<ByteArray>().also { readEntryNames(fd.fd, it) }

    private external fun readEntryNames(fd: Int, out: ArrayList<ByteArray>)
}

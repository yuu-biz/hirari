package io.github.yuubiz.hirari.core.source

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.IOException

/** An editable name and its fixed extension (docs/spec.md section 4.3). */
data class EditableName(val stem: String, val extension: String) {
    fun fullName(stem: String): String = stem + extension

    companion object {
        fun of(name: String, directory: Boolean): EditableName {
            val dot = if (directory) -1 else name.lastIndexOf('.').takeIf { it > 0 } ?: -1
            return if (dot < 0) EditableName(name, "") else EditableName(name.substring(0, dot), name.substring(dot))
        }
    }
}

fun validFileStem(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
    name.none { it in "/\\:*?\"<>|" || it.isISOControl() }

class NameExistsException(val name: String) : IOException("name already exists")

const val DELETE_COUNT_LIMIT = 1_000

/** Counts descendants, including directories, without walking past the cap. The listing may suspend for I/O. */
suspend fun countDescendants(root: String, children: suspend (String) -> List<Pair<String, Boolean>>, progress: (Int) -> Unit): Int {
    val pending = ArrayDeque<String>()
    pending.add(root)
    var count = 0
    while (pending.isNotEmpty()) {
        currentCoroutineContext().ensureActive()
        for ((path, directory) in children(pending.removeLast())) {
            currentCoroutineContext().ensureActive()
            count++
            progress(count)
            if (count == DELETE_COUNT_LIMIT) return count
            if (directory) pending.add(path)
        }
    }
    return count
}

/** SAF mutations are blocking Binder calls; a folder delete is delegated to the provider. */
suspend fun ContentResolver.renameInTree(tree: Uri, path: String, name: String) = withContext(Dispatchers.IO) {
    val id = locateDocument(tree, path) ?: throw FileNotFoundException()
    val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    DocumentsContract.renameDocument(this@renameInTree, uri, name) ?: throw IOException("rename failed")
    Unit
}

suspend fun ContentResolver.deleteInTree(tree: Uri, path: String) = withContext(Dispatchers.IO) {
    val id = locateDocument(tree, path) ?: throw FileNotFoundException()
    if (!DocumentsContract.deleteDocument(this@deleteInTree, DocumentsContract.buildDocumentUriUsingTree(tree, id))) {
        throw IOException("delete failed")
    }
}

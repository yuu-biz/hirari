package io.github.yuubiz.hirari.feature.library

/** A folder above the current one, which the title's menu goes up to (docs/spec.md section 4.3, 上部バー). */
data class Ancestor(val name: String, val path: String)

/**
 * Where the file list is in its place: the [current] folder's name, the [ancestors] from the place's top down to the
 * parent, and the [parent]'s path, `null` at the top of the place.
 */
data class FolderTrail(val current: String, val ancestors: List<Ancestor>, val parent: String?)

fun folderTrail(placeName: String, path: String): FolderTrail {
    val names = path.split('/').filter { it.isNotEmpty() }
    if (names.isEmpty()) return FolderTrail(placeName, emptyList(), parent = null)
    val ancestors = listOf(Ancestor(placeName, "")) + names.dropLast(1).mapIndexed { i, name -> Ancestor(name, names.take(i + 1).joinToString("/")) }
    return FolderTrail(names.last(), ancestors, ancestors.last().path)
}

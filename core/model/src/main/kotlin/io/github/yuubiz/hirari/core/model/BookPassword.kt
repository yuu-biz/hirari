package io.github.yuubiz.hirari.core.model

/** Passwords for one book (3.8). Returned arrays belong to the caller and must be cleared. */
interface BookPassword {
    suspend fun initial(): CharArray?
    suspend fun retry(wrong: Boolean): CharArray?
    suspend fun accepted()
    /** Drops an unverified answer when its read fails or is cancelled. */
    fun discard() = Unit
}

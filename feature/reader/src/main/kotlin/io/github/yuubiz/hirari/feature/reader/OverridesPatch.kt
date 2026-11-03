package io.github.yuubiz.hirari.feature.reader

import io.github.yuubiz.hirari.core.model.BookOverrides

private class Field<T>(private val get: (BookOverrides) -> T, private val set: (BookOverrides, T) -> BookOverrides) {
    fun setTo(after: BookOverrides): (BookOverrides) -> BookOverrides {
        val value = get(after)
        return { set(it, value) }
    }

    fun differs(before: BookOverrides, after: BookOverrides): Boolean = get(before) != get(after)
}

private val fields: List<Field<*>> = listOf(
    Field({ it.direction }, { o, v -> o.copy(direction = v) }),
    Field({ it.layoutMode }, { o, v -> o.copy(layoutMode = v) }),
    Field({ it.coverAlone }, { o, v -> o.copy(coverAlone = v) }),
    Field({ it.scaleMode }, { o, v -> o.copy(scaleMode = v) }),
    Field({ it.trim }, { o, v -> o.copy(trim = v) }),
    Field({ it.rotation }, { o, v -> o.copy(rotation = v) }),
    Field({ it.reverseOrder }, { o, v -> o.copy(reverseOrder = v) }),
    Field({ it.nameCharset }, { o, v -> o.copy(nameCharset = v) }),
    Field({ it.adjust }, { o, v -> o.copy(adjust = v) }),
)

/**
 * The fields a reader changed, each with the value it set (docs/spec.md section 2.9). Applying it sets those fields
 * and keeps every other, so it can be applied onto a newer stored row without undoing what was stored for the other
 * fields, and applying it twice gives the same row as once.
 */
internal class OverridesPatch private constructor(private val sets: Map<Field<*>, (BookOverrides) -> BookOverrides>) {
    /** [later] wins for the fields both set. */
    operator fun plus(later: OverridesPatch): OverridesPatch = OverridesPatch(sets + later.sets)

    fun applyTo(overrides: BookOverrides): BookOverrides = sets.values.fold(overrides) { row, set -> set(row) }

    companion object {
        /** The fields [after] holds differently from [before], or null when it holds none. */
        fun between(before: BookOverrides, after: BookOverrides): OverridesPatch? {
            val changed = fields.filter { it.differs(before, after) }
            return if (changed.isEmpty()) null else OverridesPatch(changed.associateWith { it.setTo(after) })
        }
    }
}

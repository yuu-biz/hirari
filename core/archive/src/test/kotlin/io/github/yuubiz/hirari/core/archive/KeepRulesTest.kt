package io.github.yuubiz.hirari.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Method

/**
 * libarchive_jni.c finds the callbacks of [NativeArchive] by name and signature, and R8 keeps them by the rules of
 * consumer-rules.pro. A signature that drifts from either passes every JVM test and fails only in a minified build.
 */
class KeepRulesTest {
    private val lookups = Regex("""GetMethodID\(env, cls, "(\w+)", "([^"]+)"\)""")
        .findAll(File("src/main/cpp/libarchive_jni.c").readText())
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()

    private val rules = File("consumer-rules.pro").readLines().map { it.trim() }

    private fun method(name: String): Method =
        NativeArchive::class.java.declaredMethods.single { it.name == name }

    private fun Class<*>.descriptor(): String = when {
        this == Void.TYPE -> "V"
        this == java.lang.Long.TYPE -> "J"
        this == Integer.TYPE -> "I"
        this == java.lang.Boolean.TYPE -> "Z"
        this == java.lang.Byte.TYPE -> "B"
        isArray -> "[" + componentType!!.descriptor()
        else -> "L" + name.replace('.', '/') + ";"
    }

    private fun Method.descriptor() = parameterTypes.joinToString("", "(", ")") { it.descriptor() } + returnType.descriptor()

    private fun Method.keepRule() =
        returnType.typeName + " " + name + parameterTypes.joinToString(", ", "(", ");") { it.typeName }

    @Test
    fun theJniLookupsAreTheCallbacksOfNativeArchive() {
        assertEquals(listOf("read", "header", "data"), lookups.map { it.first })
        for ((name, signature) in lookups) {
            assertEquals(name, signature, method(name).descriptor())
        }
    }

    @Test
    fun everyCallbackTheJniLooksUpIsKept() {
        for ((name, _) in lookups) {
            val rule = method(name).keepRule()
            assertTrue("consumer-rules.pro lacks `$rule`", rule in rules)
        }
    }
}

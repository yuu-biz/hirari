package io.github.yuubiz.hirari.core.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

/** The JVM tests run the envelope with a software key; this runs it with the AndroidKeyStore key. */
class KeystoreCipherTest {
    private val cipher = SecretCipher(::keystoreKey)

    @Test
    fun aSecretSealedWithTheKeystoreKeyOpensAgain() {
        val sealed = cipher.seal("secret".toByteArray(), "alias".toByteArray())
        assertArrayEquals("secret".toByteArray(), cipher.open(sealed, "alias".toByteArray()))
    }

    @Test
    fun theKeyNeverLeavesTheKeystore() {
        assertNull(keystoreKey().encoded)
    }

    @Test
    fun aSecretDoesNotOpenUnderAnotherAlias() {
        val sealed = cipher.seal("secret".toByteArray(), "alias".toByteArray())
        try {
            cipher.open(sealed, "other".toByteArray())
            fail("opened under another alias")
        } catch (_: GeneralSecurityException) {
        }
    }
}

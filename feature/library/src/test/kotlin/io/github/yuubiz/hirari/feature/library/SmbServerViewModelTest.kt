package io.github.yuubiz.hirari.feature.library

import io.github.yuubiz.hirari.core.data.PasswordChange
import io.github.yuubiz.hirari.core.model.CredentialRef
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.locationBookId
import io.github.yuubiz.hirari.core.source.smb.DiscoveredShare
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryResult
import io.github.yuubiz.hirari.core.source.smb.ShareDiscoveryFailure
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import io.github.yuubiz.hirari.core.data.SmbServer as ServerForm

@OptIn(ExperimentalCoroutinesApi::class)
class SmbServerViewModelTest {
    private val fixture = Fixture()
    private val nas = SmbFields(host = "nas.local", share = "books", folder = "漫画/", user = "reader")

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun form(id: LocationId? = null) = SmbServerViewModel(CompletableDeferred(fixture.services), id)

    private fun SmbServerViewModel.editing() = assertIs<SmbFormUi.Editing>(state.value)

    @Test
    fun `discovery needs a host but no share and choosing only fills the share`() = runTest {
        val model = form()
        assertFalse(model.editing().canDiscover)
        model.edit(nas.copy(share = "", port = "14455", domain = "WORKGROUP"))
        assertTrue(model.editing().canDiscover)
        val offered = DiscoveredShare("books2", 0, "漫画")
        fixture.smb.discovering = { ShareDiscoveryResult.Listed(listOf(offered)) }
        val typed = "secret".toCharArray()
        model.discover(typed)
        assertTrue(typed.all { it == '\u0000' })
        val sent = fixture.smb.discoveries.single().first
        assertEquals("nas.local", sent.host)
        assertEquals(14455, sent.port)
        assertEquals("WORKGROUP", sent.domain)
        assertEquals("reader", sent.user)
        assertEquals("secret", fixture.smb.discoveries.single().second)
        assertEquals("", model.editing().fields.share)
        model.chooseShare(offered)
        assertEquals(nas.copy(share = "books2", port = "14455", domain = "WORKGROUP"), model.editing().fields)
        assertEquals(null, model.editing().discovery)
        assertTrue(fixture.locations.rows.value.isEmpty())
    }

    @Test
    fun `every discovery failure preserves the entire form`() = runTest {
        val model = form()
        model.edit(nas)
        for (failure in ShareDiscoveryFailure.entries) {
            fixture.smb.discovering = { ShareDiscoveryResult.Failed(failure) }
            model.discover(null)
            assertEquals(ShareDiscoveryResult.Failed(failure), model.editing().discovery)
            assertEquals(nas, model.editing().fields)
            model.cancelDiscovery()
            assertEquals(null, model.editing().discovery)
        }
        assertTrue(fixture.locations.rows.value.isEmpty())
    }

    @Test
    fun `cancel stops discovery and wipes the password without changing the form`() = runTest {
        val model = form()
        model.edit(nas)
        var cancelled = false
        fixture.smb.discovering = { try { awaitCancellation() } finally { cancelled = true } }
        val typed = "secret".toCharArray()
        model.discover(typed)
        assertTrue(model.editing().discovering)
        assertFalse(model.editing().canDiscover)
        model.cancelDiscovery()
        assertTrue(cancelled)
        assertFalse(model.editing().discovering)
        assertTrue(model.editing().canDiscover)
        assertEquals(null, model.editing().discovery)
        assertEquals(nas, model.editing().fields)
        assertTrue(typed.all { it == '\u0000' })
    }

    @Test
    fun `an edit cancels a pending discovery and ignores its late result`() = runTest {
        val model = form()
        model.edit(nas)
        fixture.smb.discovering = {
            try { awaitCancellation() } catch (_: kotlinx.coroutines.CancellationException) {
                ShareDiscoveryResult.Listed(listOf(DiscoveredShare("late", 0)))
            }
        }
        model.discover(null)
        model.edit(nas.copy(host = "other"))
        assertEquals(null, model.editing().discovery)
        assertFalse(model.editing().discovering)
        assertEquals("other", model.editing().fields.host)
        assertEquals(nas.share, model.editing().fields.share)
    }

    @Test
    fun `a form checks each field and fills what may be left blank`() {
        assertEquals(
            FormCheck.Invalid(mapOf(SmbField.Host to SmbFieldError.Required, SmbField.Share to SmbFieldError.Required)),
            SmbFields().check(passwordTyped = false),
        )
        assertEquals(
            FormCheck.Invalid(
                mapOf(
                    SmbField.Host to SmbFieldError.Invalid,
                    SmbField.Port to SmbFieldError.Invalid,
                    SmbField.Share to SmbFieldError.Invalid,
                    SmbField.User to SmbFieldError.Required,
                ),
            ),
            SmbFields(host = "nas local", port = "70000", share = "a/b").check(passwordTyped = true),
        )
        assertEquals(
            FormCheck.Valid(ServerForm("nas.local/books", "nas.local", 445, "books", "漫画", domain = null, user = null)),
            SmbFields(host = " smb://nas.local/ ", port = "", share = "books", folder = "/漫画/").check(passwordTyped = false),
        )
    }

    @Test
    fun `a new server is saved with its password and the form closes`() = runTest {
        val model = form()
        model.edit(nas)
        model.save("pw-1".toCharArray())

        assertEquals(SmbFormUi.Saved, model.state.value)
        val place = assertIs<LibraryLocation.SmbShare>(fixture.locations.rows.value.single())
        assertEquals(listOf("nas.local", "books", "漫画", "reader"), listOf(place.host, place.share, place.basePath, place.user))
        assertEquals("pw-1", fixture.locations.secrets[place.credential])
    }

    @Test
    fun `an invalid form saves nothing and shows why, and a guest keeps no password`() = runTest {
        val model = form()
        val typed = "pw".toCharArray()
        model.edit(nas.copy(share = "", user = ""))
        model.save(typed)

        assertEquals(mapOf(SmbField.Share to SmbFieldError.Required, SmbField.User to SmbFieldError.Required), model.editing().errors)
        assertTrue(typed.all { it == '\u0000' }, "the typed password is wiped")
        assertTrue(fixture.locations.rows.value.isEmpty())

        model.edit(nas.copy(user = ""))
        model.save(CharArray(0))
        assertEquals(null, (fixture.locations.rows.value.single() as LibraryLocation.SmbShare).credential)
    }

    @Test
    fun `an edit shows no password, keeps it unless one is typed, and reconnects`() = runTest {
        val id = fixture.locations.addSmbShare(ServerForm("NAS", "nas.local", share = "books", user = "reader"), "pw-7Qx".toCharArray())
        val model = form(id)
        val shown = model.editing()
        assertEquals("reader", shown.fields.user)
        assertTrue(shown.storedPassword)
        assertFalse(shown.toString().contains("pw-7Qx"))

        model.save(CharArray(0))
        assertEquals(listOf<PasswordChange>(PasswordChange.Keep), fixture.locations.changes)

        val again = form(id)
        again.save("new".toCharArray())
        assertIs<PasswordChange.Set>(fixture.locations.changes.last())
        assertEquals("new", fixture.locations.secrets[CredentialRef("ref-${id.value}")])
        assertEquals(listOf(id, id), fixture.smb.forgotten)
    }

    @Test
    fun `a rejected save stays on the form, so the password typed for it is saved once the form is fixed`() = runTest {
        val id = fixture.locations.addSmbShare(ServerForm("NAS", "nas.local", share = "books", user = "reader"), "pw-7Qx".toCharArray())
        val model = form(id)
        model.edit(model.editing().fields.copy(share = ""))
        model.save("new".toCharArray())
        assertEquals(mapOf(SmbField.Share to SmbFieldError.Required), model.editing().errors)
        assertTrue(fixture.locations.changes.isEmpty())

        model.edit(model.editing().fields.copy(share = "books"))
        model.save("new".toCharArray())
        assertEquals(SmbFormUi.Saved, model.state.value)
        assertEquals("new", fixture.locations.secrets[CredentialRef("ref-${id.value}")])
    }

    @Test
    fun `a new password leaves the books of the server where they were`() = runTest {
        val id = fixture.locations.addSmbShare(ServerForm("NAS", "nas.local", share = "books", user = "reader"), "pw-7Qx".toCharArray())
        val before = locationBookId(fixture.locations.get(id)!!, "1巻.zip")
        form(id).save("new".toCharArray())

        assertEquals(before, locationBookId(fixture.locations.get(id)!!, "1巻.zip"))
    }

    @Test
    fun `removing the password or the user removes the stored one`() = runTest {
        val id = fixture.locations.addSmbShare(ServerForm("NAS", "nas.local", share = "books", user = "reader"), "pw-7Qx".toCharArray())
        val model = form(id)
        model.setRemovePassword(true)
        model.save(null)
        assertEquals(PasswordChange.Remove, fixture.locations.changes.last())

        val guest = form(id)
        guest.edit(guest.editing().fields.copy(user = ""))
        guest.save(null)
        assertEquals(PasswordChange.Remove, fixture.locations.changes.last())
    }

    @Test
    fun `a test tries the typed or the stored password and shows the result`() = runTest {
        val id = fixture.locations.addSmbShare(ServerForm("NAS", "nas.local", share = "books", user = "reader"), "pw-7Qx".toCharArray())
        val model = form(id)
        model.test(null)
        assertEquals(ConnectionTest.Passed, model.editing().test)
        assertEquals(null to CredentialRef("ref-${id.value}"), fixture.smb.tests.last().let { it.second to it.third })

        fixture.smb.result = SmbTestResult.Failed(SmbTestFailure.AuthenticationFailed)
        val typed = "typo".toCharArray()
        model.test(typed)
        assertEquals(ConnectionTest.Failed(SmbTestFailure.AuthenticationFailed), model.editing().test)
        assertEquals("typo", fixture.smb.tests.last().second)
        assertTrue(typed.all { it == '\u0000' }, "the typed password is wiped")

        model.edit(model.editing().fields.copy(host = "nas2.local"))
        assertEquals(ConnectionTest.Idle, model.editing().test)
    }
}

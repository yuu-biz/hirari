package io.github.yuubiz.hirari.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.yuubiz.hirari.core.data.PasswordChange
import io.github.yuubiz.hirari.core.model.LibraryLocation
import io.github.yuubiz.hirari.core.model.LocationId
import io.github.yuubiz.hirari.core.model.normalizePath
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import io.github.yuubiz.hirari.core.data.SmbServer as ServerForm

/** The text fields of the SMB form (docs/spec.md section 4.5), as typed. The password is never among them. */
data class SmbFields(
    val displayName: String = "",
    val host: String = "",
    val port: String = SMB_PORT.toString(),
    val share: String = "",
    val folder: String = "",
    val domain: String = "",
    val user: String = "",
)

enum class SmbField { Host, Port, Share, User }

enum class SmbFieldError { Required, Invalid }

sealed interface FormCheck {
    data class Valid(val server: ServerForm) : FormCheck

    data class Invalid(val errors: Map<SmbField, SmbFieldError>) : FormCheck
}

/**
 * The server [this] form describes, or what is wrong with it (docs/spec.md section 4.5). A host typed as
 * `smb://host` or `\\host` loses the prefix. A blank name becomes `host/share`, a blank port 445, and a blank
 * domain or user none. A password needs a user, since the guest takes neither.
 */
fun SmbFields.check(passwordTyped: Boolean): FormCheck {
    val errors = mutableMapOf<SmbField, SmbFieldError>()
    val host = host.trim().removePrefix("smb://").removePrefix("\\\\").trimEnd('/', '\\')
    when {
        host.isEmpty() -> errors[SmbField.Host] = SmbFieldError.Required
        host.any { it.isWhitespace() || it == '/' || it == '\\' } -> errors[SmbField.Host] = SmbFieldError.Invalid
    }
    val port = if (port.isBlank()) SMB_PORT else port.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    if (port == null) errors[SmbField.Port] = SmbFieldError.Invalid
    val share = share.trim().trim('/', '\\')
    when {
        share.isEmpty() -> errors[SmbField.Share] = SmbFieldError.Required
        share.any { it == '/' || it == '\\' } -> errors[SmbField.Share] = SmbFieldError.Invalid
    }
    val user = user.trim().ifEmpty { null }
    if (user == null && passwordTyped) errors[SmbField.User] = SmbFieldError.Required
    if (errors.isNotEmpty() || port == null) return FormCheck.Invalid(errors)
    return FormCheck.Valid(
        ServerForm(
            displayName = displayName.trim().ifEmpty { "$host/$share" },
            host = host,
            port = port,
            share = share,
            basePath = normalizePath(folder.trim()),
            domain = domain.trim().ifEmpty { null },
            user = user,
        ),
    )
}

sealed interface ConnectionTest {
    data object Idle : ConnectionTest

    data object Running : ConnectionTest

    data object Passed : ConnectionTest

    data class Failed(val reason: SmbTestFailure) : ConnectionTest
}

sealed interface SmbFormUi {
    data object Loading : SmbFormUi

    /** The server being edited is gone. */
    data object Missing : SmbFormUi

    /**
     * [storedPassword] tells that the edited server keeps a password, which the form never shows; [removePassword]
     * drops it on saving. [errors] are of the last save or test.
     */
    data class Editing(
        val fields: SmbFields,
        val editing: Boolean,
        val storedPassword: Boolean = false,
        val removePassword: Boolean = false,
        val errors: Map<SmbField, SmbFieldError> = emptyMap(),
        val test: ConnectionTest = ConnectionTest.Idle,
        val saving: Boolean = false,
    ) : SmbFormUi

    /** Saved; the screen closes. */
    data object Saved : SmbFormUi
}

/**
 * The SMB form of docs/spec.md section 4.5: a new server when [id] is null, else that server. The password comes
 * in as a `CharArray` with [test] and [save], which wipe it, so it never sits in the state.
 */
class SmbServerViewModel(private val services: Deferred<LibraryServices>, private val id: LocationId?) : ViewModel() {
    private val mutableState = MutableStateFlow<SmbFormUi>(if (id == null) SmbFormUi.Editing(SmbFields(), editing = false) else SmbFormUi.Loading)
    val state: StateFlow<SmbFormUi> = mutableState.asStateFlow()

    private var stored: LibraryLocation.SmbShare? = null
    private var testing: Job? = null

    init {
        if (id != null) {
            viewModelScope.launch {
                val place = services.await().locations.get(id) as? LibraryLocation.SmbShare
                stored = place
                mutableState.value = place?.let { SmbFormUi.Editing(it.fields(), editing = true, storedPassword = it.credential != null) } ?: SmbFormUi.Missing
            }
        }
    }

    fun edit(fields: SmbFields) = editing { it.copy(fields = fields, test = ConnectionTest.Idle) }

    fun setRemovePassword(remove: Boolean) = editing { it.copy(removePassword = remove, test = ConnectionTest.Idle) }

    /** 接続を試す with what the form holds. [password] is null or empty to keep the stored one. */
    fun test(password: CharArray?) {
        val form = mutableState.value as? SmbFormUi.Editing ?: return password.wipe()
        val typed = password?.takeIf { it.isNotEmpty() }
        val server = when (val checked = form.fields.check(typed != null)) {
            is FormCheck.Invalid -> return editing { it.copy(errors = checked.errors) }.also { password.wipe() }
            is FormCheck.Valid -> checked.server
        }
        testing?.cancel()
        editing { it.copy(errors = emptyMap(), test = ConnectionTest.Running) }
        val kept = stored?.credential?.takeUnless { form.removePassword }
        testing = viewModelScope.launch {
            val result = try {
                services.await().smb.test(server, typed, kept)
            } finally {
                password.wipe()
            }
            editing { it.copy(test = if (result is SmbTestResult.Failed) ConnectionTest.Failed(result.reason) else ConnectionTest.Passed) }
        }
    }

    /**
     * Adds the server, or edits it in place, so its books keep their progress whatever the password becomes
     * (docs/spec.md section 2.2). An empty [password] keeps the stored one; a server without a user keeps none.
     */
    fun save(password: CharArray?) {
        val form = mutableState.value as? SmbFormUi.Editing ?: return password.wipe()
        if (form.saving) return password.wipe()
        val typed = password?.takeIf { it.isNotEmpty() }
        val server = when (val checked = form.fields.check(typed != null)) {
            is FormCheck.Invalid -> return editing { it.copy(errors = checked.errors) }.also { password.wipe() }
            is FormCheck.Valid -> checked.server
        }
        editing { it.copy(errors = emptyMap(), saving = true) }
        viewModelScope.launch {
            try {
                val services = services.await()
                if (id == null) {
                    services.locations.addSmbShare(server, typed?.takeIf { server.user != null })
                } else {
                    val change = when {
                        server.user == null || form.removePassword && typed == null -> PasswordChange.Remove
                        typed != null -> PasswordChange.Set(typed)
                        else -> PasswordChange.Keep
                    }
                    services.locations.updateSmbShare(id, server, change)
                    services.smb.forget(id)
                }
            } finally {
                password.wipe()
            }
            mutableState.value = SmbFormUi.Saved
        }
    }

    private fun editing(change: (SmbFormUi.Editing) -> SmbFormUi.Editing) = mutableState.update { if (it is SmbFormUi.Editing) change(it) else it }
}

private fun CharArray?.wipe() {
    this?.fill('\u0000')
}

private fun LibraryLocation.SmbShare.fields() = SmbFields(
    displayName = displayName,
    host = host,
    port = port.toString(),
    share = share,
    folder = basePath,
    domain = domain.orEmpty(),
    user = user.orEmpty(),
)

const val SMB_PORT = 445

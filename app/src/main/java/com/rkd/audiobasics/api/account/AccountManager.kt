package com.rkd.audiobasics.api.account

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** What the login WebView read out of a signed-in music.youtube.com page. */
class CapturedLogin(
    internal val cookie: String,
    internal val visitorData: String,
    internal val dataSyncId: String,
    internal val authUser: String
)

sealed interface LoginResult {
    data object Success : LoginResult
    data class Failed(val message: String) : LoginResult
}

/**
 * The single owner of "who is signed in". Everything that changes the signed-in state goes
 * through [opLock], so a login, a logout and a session check can never interleave.
 *
 * Rules this class enforces:
 * - A login only counts once YouTube has confirmed the account AND the session has been
 *   saved securely. Anything less leaves the previous state untouched.
 * - A session is marked expired only on an explicit rejection from YouTube. Offline, timeouts,
 *   429s and unexpected responses never change the state.
 */
object AccountManager {

    private const val TAG = "Account"
    private const val CHECK_COOLDOWN_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val opLock = Mutex()

    private var store: AuthStore? = null

    @Volatile
    private var session: AccountSession? = null

    private val _state = MutableStateFlow<AccountState>(AccountState.LoggedOut)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    /** Call once from Application.onCreate. Safe to call again. */
    @Synchronized
    fun init(context: Context) {
        if (store != null) return
        val authStore = AuthStore(context.applicationContext)
        store = authStore

        val loaded = authStore.load()
        if (loaded == null) {
            Timber.tag(TAG).d("No stored account session")
            return
        }
        session = loaded
        _state.value = AccountState.LoggedIn(loaded.profile, loaded.libraryLinked)
        Timber.tag(TAG).d("Restored a stored account session; checking it with YouTube")
        scope.launch { validateSession() }
    }

    /**
     * The live session for authenticated requests, or null when signed out or expired.
     * Used by later phases (playback, sync); never exposed to the UI.
     */
    internal fun activeSession(): AccountSession? =
        if (_state.value is AccountState.LoggedIn) session else null

    @Volatile
    private var lastCheckRequestMs = 0L

    /**
     * Fire-and-forget [validateSession] for callers that just saw a sign-in rejection (e.g. the
     * player endpoint). Throttled, so a run of failing songs triggers at most one check per
     * few minutes. Does nothing when no account is linked.
     */
    internal fun requestSessionCheck() {
        if (session == null) return
        val now = System.currentTimeMillis()
        if (now - lastCheckRequestMs < CHECK_COOLDOWN_MS) return
        lastCheckRequestMs = now
        scope.launch { validateSession() }
    }

    /** Called when any request gets an explicit "not signed in" answer from YouTube. */
    internal fun reportSessionRejected() {
        val current = session ?: return
        if (_state.value is AccountState.LoggedIn) {
            Timber.tag(TAG).w("A request was rejected as unauthenticated; marking the session expired")
            _state.value = AccountState.SessionExpired(current.profile)
        }
    }

    /**
     * Asks YouTube whether the stored session still works. Only an explicit rejection
     * changes anything; every other outcome is logged and ignored.
     */
    suspend fun validateSession() {
        opLock.withLock {
            val current = session ?: return@withLock
            val result = withContext(Dispatchers.IO) {
                AccountClient.fetchAccountInfo(current.cookie, current.visitorData, current.authUser)
            }
            when (result) {
                is AccountResult.Success -> {
                    val info = result.value
                    val profile = current.profile.copy(
                        name = info.name,
                        email = info.email ?: current.profile.email,
                        handle = info.handle ?: current.profile.handle,
                        photoUrl = info.photoUrl ?: current.profile.photoUrl
                    )
                    val updated = current.copy(profile = profile)
                    if (profile != current.profile && store?.save(updated) != true) {
                        Timber.tag(TAG).w("Session is valid but the refreshed profile couldn't be saved")
                    }
                    session = updated
                    _state.value = AccountState.LoggedIn(profile, updated.libraryLinked)
                    Timber.tag(TAG).d("Session check: valid")
                }
                is AccountResult.SessionExpired -> {
                    Timber.tag(TAG).w("Session check: YouTube no longer accepts this session")
                    _state.value = AccountState.SessionExpired(current.profile)
                }
                is AccountResult.NetworkError ->
                    Timber.tag(TAG).d("Session check skipped: no connection (state unchanged)")
                is AccountResult.ServerError ->
                    Timber.tag(TAG).w("Session check inconclusive: HTTP ${result.code} (state unchanged)")
                is AccountResult.ParseError ->
                    Timber.tag(TAG).w("Session check inconclusive: ${result.message} (state unchanged)")
            }
        }
    }

    /** Turns what the login page captured into a saved, confirmed session. */
    suspend fun completeLogin(captured: CapturedLogin): LoginResult = opLock.withLock {
        if (!SapisidAuth.hasAuthCookie(captured.cookie)) {
            Timber.tag(TAG).w("Login: the captured session has no auth cookie")
            return@withLock LoginResult.Failed("Sign-in didn't finish. Please try again.")
        }

        val result = withContext(Dispatchers.IO) {
            AccountClient.fetchAccountInfo(captured.cookie, captured.visitorData, captured.authUser)
        }
        val info = when (result) {
            is AccountResult.Success -> result.value
            is AccountResult.SessionExpired -> {
                Timber.tag(TAG).w("Login: YouTube Music didn't accept the new session")
                return@withLock LoginResult.Failed("YouTube Music didn't accept the sign-in. Please try again.")
            }
            is AccountResult.NetworkError -> {
                Timber.tag(TAG).w("Login: no connection while confirming the account")
                return@withLock LoginResult.Failed("No connection. Check your internet and try again.")
            }
            is AccountResult.ServerError -> {
                Timber.tag(TAG).w("Login: confirming the account failed with HTTP ${result.code}")
                return@withLock LoginResult.Failed("YouTube is having trouble right now (${result.code}). Try again in a bit.")
            }
            is AccountResult.ParseError -> {
                Timber.tag(TAG).w("Login: unexpected response while confirming the account: ${result.message}")
                return@withLock LoginResult.Failed("YouTube sent an unexpected reply. Please try again.")
            }
        }

        val accountId = captured.dataSyncId.substringBefore("||")
            .ifBlank { info.email ?: info.name }
        // Signing back in to the SAME account after an expired session keeps the library link:
        // that library already belongs to this account, so the login questions don't repeat.
        // A different account, or any login after a logout, always starts unlinked.
        val keepLink = session?.let { it.profile.accountId == accountId && it.libraryLinked } == true
        val newSession = AccountSession(
            cookie = captured.cookie,
            visitorData = captured.visitorData,
            dataSyncId = captured.dataSyncId,
            authUser = captured.authUser,
            profile = AccountProfile(
                accountId = accountId,
                name = info.name,
                email = info.email,
                handle = info.handle,
                photoUrl = info.photoUrl
            ),
            libraryLinked = keepLink
        )

        val saved = store?.save(newSession) == true
        if (!saved) {
            return@withLock LoginResult.Failed("Couldn't save the sign-in securely on this device.")
        }

        session = newSession
        _state.value = AccountState.LoggedIn(newSession.profile, keepLink)
        Timber.tag(TAG).d("Login complete (library linked: $keepLink)")
        LoginResult.Success
    }

    /**
     * Records that this device's library has (or no longer has) been reconciled with the account.
     * Persisted, so an app restart doesn't ask the login questions again or skip them.
     */
    internal suspend fun markLibraryLinked(linked: Boolean): Boolean = opLock.withLock {
        val current = session ?: return@withLock false
        val updated = current.copy(libraryLinked = linked)
        if (store?.save(updated) != true) {
            Timber.tag(TAG).w("Couldn't persist the library link state")
            return@withLock false
        }
        session = updated
        if (_state.value is AccountState.LoggedIn) {
            _state.value = AccountState.LoggedIn(updated.profile, linked)
        }
        true
    }

    /**
     * Forgets the account on this device and clears the browser session the login page used,
     * so the next sign-in starts from a clean slate and can pick a different account.
     */
    suspend fun logout() {
        opLock.withLock {
            store?.clear()
            session = null
            _state.value = AccountState.LoggedOut
            Timber.tag(TAG).d("Logged out; stored session removed")
        }
        clearWebSession()
    }

    /** Wipes cookies and page storage from the in-app browser. Must run on the main thread. */
    suspend fun clearWebSession() {
        withContext(Dispatchers.Main) {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Couldn't clear the in-app browser session")
            }
        }
    }
}

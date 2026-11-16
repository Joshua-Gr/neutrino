package io.github.joshuagr.neutrino.auth

import android.util.Log
import io.github.joshuagr.neutrino.api.ApiResponse
import io.github.joshuagr.neutrino.api.ProtonApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Owns the current session: persists it, adds it to requests, and refreshes it on a 401. The Drive
 * SDK never refreshes on its own, so every authenticated path in the app goes through here.
 */
class SessionManager(private val store: SessionStore, private val api: ProtonApi) {
    private val refreshLock = Mutex()
    private val state = MutableStateFlow(store.load())
    val session: StateFlow<Session?> = state.asStateFlow()

    fun current(): Session? = state.value

    fun signedIn(session: Session) {
        store.save(session)
        state.value = session
    }

    fun authHeaders(session: Session): Map<String, String> =
        mapOf("x-pm-uid" to session.uid, "Authorization" to "Bearer ${session.accessToken}")

    /** An authenticated JSON call, retried once after a successful refresh if the token had expired. */
    suspend fun call(method: String, path: String, body: JSONObject? = null): ApiResponse {
        val session = state.value ?: throw NotSignedInException()
        val first = api.call(method, path, body, authHeaders(session))
        if (first.status != HTTP_UNAUTHORIZED) return first
        val renewed = refresh(session) ?: return first
        return api.call(method, path, body, authHeaders(renewed))
    }

    /**
     * Refreshes [stale] unless another caller already did. Returns the usable session, or null when
     * the refresh token was rejected, in which case the session is dropped and the user must sign in.
     */
    suspend fun refresh(stale: Session): Session? = refreshLock.withLock {
        state.value?.takeIf { it.accessToken != stale.accessToken }?.let { return it }
        val response = api.call(
            "POST",
            "/auth/v4/refresh",
            JSONObject()
                .put("ResponseType", "token")
                .put("GrantType", "refresh_token")
                .put("RefreshToken", stale.refreshToken),
            mapOf("x-pm-uid" to stale.uid),
        )
        when {
            response.ok -> stale.also { Log.i(TAG, "Session refreshed: HTTP ${response.status}") }.withTokens(
                uid = response.body.optString("UID").ifEmpty { stale.uid },
                accessToken = response.body.getString("AccessToken"),
                refreshToken = response.body.optString("RefreshToken").ifEmpty { stale.refreshToken },
            ).also(::signedIn)
            response.status in HTTP_CLIENT_ERRORS && response.status != HTTP_TOO_MANY -> {
                Log.w(TAG, "Session refresh rejected: HTTP ${response.status}, code ${response.code}")
                forget()
                null
            }
            else -> null
        }
    }

    /** Debug builds only: invalidates the access token so the refresh path can be exercised on demand. */
    fun debugExpireAccessToken() {
        val session = state.value ?: return
        signedIn(session.withTokens(session.uid, "invalid-for-test", session.refreshToken))
        Log.i(TAG, "Debug: access token invalidated")
    }

    /** Revokes the session on the server (best effort) and forgets it locally. */
    suspend fun signOut() {
        state.value?.let { session ->
            runCatching { api.call("DELETE", "/auth/v4", headers = authHeaders(session)) }
                .onSuccess { Log.i(TAG, "Session revoke: HTTP ${it.status}") }
                .onFailure { Log.w(TAG, "Session revoke failed: ${it::class.simpleName}") }
        }
        forget()
    }

    private fun forget() {
        store.clear()
        state.value = null
    }

    class NotSignedInException : IllegalStateException("Not signed in")

    private companion object {
        const val TAG = "Neutrino"
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY = 429
        val HTTP_CLIENT_ERRORS = 400..499
    }
}

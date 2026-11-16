package io.github.joshuagr.neutrino.auth

import android.util.Base64
import io.github.joshuagr.neutrino.api.ProtonApi
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Browser sign-in through a Proton session fork: the user signs in on Proton's own page, and this
 * app receives the session plus the key password, encrypted with a one-time key that only ever
 * travels in the URL fragment. The app never sees the account password.
 */
class ForkLogin(private val api: ProtonApi) {

    /** A started sign-in: the page to open, and what is needed to collect the result. */
    class Pending internal constructor(val signInUrl: String, internal val selector: String, internal val key: ByteArray) {
        override fun toString() = "Pending(<redacted>)"
    }

    suspend fun start(): Pending {
        val init = api.call("GET", "/auth/v4/sessions/forks")
        check(init.ok) { "Could not start sign-in (HTTP ${init.status}, code ${init.code})" }
        val key = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
        val payload = "0:${init.body.getString("UserCode")}:${Base64.encodeToString(key, Base64.NO_WRAP)}:$CLIENT_ID"
        val url = "${ProtonApi.ACCOUNT_BASE}/desktop/login?app=drive&pv=3#payload=" +
            URLEncoder.encode(payload, "UTF-8").replace("+", "%20")
        return Pending(url, init.body.getString("Selector"), key)
    }

    /** Waits for the browser sign-in to complete. Network blips are retried; the wait is bounded. */
    suspend fun await(pending: Pending): Session {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        delay(POLL_MS)
        while (System.currentTimeMillis() < deadline) {
            val status = try {
                api.call("GET", "/auth/v4/sessions/forks/${pending.selector}")
            } catch (_: IOException) {
                null
            }
            when {
                status == null || status.status == HTTP_NOT_READY -> delay(POLL_MS)
                status.ok -> return sessionFrom(status.body, pending.key)
                else -> error("Sign-in failed (HTTP ${status.status}, code ${status.code})")
            }
        }
        error("Sign-in timed out")
    }

    private fun sessionFrom(body: JSONObject, key: ByteArray): Session {
        val blob = Base64.decode(body.getString("Payload"), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(AAD)
        val payload = JSONObject(String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)))
        return Session(
            uid = body.getString("UID"),
            accessToken = body.getString("AccessToken"),
            refreshToken = body.getString("RefreshToken"),
            keyPassword = payload.getString("keyPassword"),
        )
    }

    private companion object {
        /** Proton's designated client id for third-party Drive applications. */
        const val CLIENT_ID = "external-drive"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
        const val HTTP_NOT_READY = 422
        const val POLL_MS = 5_000L
        const val MAX_WAIT_MS = 10 * 60 * 1_000L
        val AAD = "fork".toByteArray()
    }
}

package io.github.joshuagr.neutrino.auth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.SecureRandom

/**
 * An authenticated Proton session, the password that unlocks the account's keys, and the key the
 * Drive SDK uses to encrypt its on-disk cache (which otherwise holds decrypted metadata in clear).
 */
class Session(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val keyPassword: String,
    val cacheKey: ByteArray = ByteArray(CACHE_KEY_BYTES).also(SecureRandom()::nextBytes),
) {
    fun withTokens(uid: String, accessToken: String, refreshToken: String) =
        Session(uid, accessToken, refreshToken, keyPassword, cacheKey)

    override fun toString() = "Session(<redacted>)"

    private companion object {
        const val CACHE_KEY_BYTES = 32
    }
}

/** Persists the session encrypted with a Keystore key, in app-private storage excluded from backup. */
class SessionStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "session.bin")
    private val cipher = KeystoreCipher("neutrino-session")

    fun load(): Session? {
        if (!file.exists()) return null
        return runCatching {
            val json = JSONObject(String(cipher.decrypt(file.readBytes())))
            Session(
                uid = json.getString("uid"),
                accessToken = json.getString("access"),
                refreshToken = json.getString("refresh"),
                keyPassword = json.getString("keyPassword"),
                cacheKey = Base64.decode(json.getString("cacheKey"), Base64.NO_WRAP),
            )
        }.getOrNull()
    }

    fun save(session: Session) {
        val json = JSONObject()
            .put("uid", session.uid)
            .put("access", session.accessToken)
            .put("refresh", session.refreshToken)
            .put("keyPassword", session.keyPassword)
            .put("cacheKey", Base64.encodeToString(session.cacheKey, Base64.NO_WRAP))
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(cipher.encrypt(json.toString().toByteArray()))
        check(tmp.renameTo(file)) { "Could not replace the session file" }
    }

    fun clear() {
        file.delete()
        cipher.deleteKey()
    }
}

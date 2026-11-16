package io.github.joshuagr.neutrino.drive

import com.proton.gopenpgp.crypto.Crypto
import com.proton.gopenpgp.crypto.KeyRing
import io.github.joshuagr.neutrino.auth.SessionManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.drive.sdk.PublicAddressResolver
import me.proton.drive.sdk.UserAddressResolver
import me.proton.drive.sdk.entity.Address
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Supplies the Drive SDK with the account's addresses and their unlocked private keys. User keys
 * are unlocked with the key password; each address key is unlocked with its token, which is
 * encrypted to and signed by the user keys. Unlocked keys live in memory only.
 */
class AccountKeys(private val sessions: SessionManager) : UserAddressResolver, PublicAddressResolver {

    private class Loaded(val address: Address, val unlocked: Map<String, ByteArray>, val primaryKeyId: String)

    private val lock = Mutex()
    private var loaded: List<Loaded>? = null
    var userId: String? = null
        private set
    var primaryEmail: String? = null
        private set

    suspend fun load(): Unit = lock.withLock {
        if (loaded != null) return
        val keyPassword = sessions.current()?.keyPassword ?: throw SessionManager.NotSignedInException()
        val user = sessions.call("GET", "/core/v4/users").requireOk("user").getJSONObject("User")
        val userRing = unlockUserKeys(user, keyPassword)
        val addresses = sessions.call("GET", "/core/v4/addresses").requireOk("addresses").getJSONArray("Addresses")
        loaded = (0 until addresses.length()).map { i -> loadAddress(addresses.getJSONObject(i), userRing, keyPassword) }
        userId = user.getString("ID")
        primaryEmail = loaded!!
            .filter { it.address.status == Address.Status.ENABLED }
            .minByOrNull { it.address.order }
            ?.address?.emailAddress
    }

    fun forget() {
        loaded = null
        userId = null
        primaryEmail = null
    }

    private fun unlockUserKeys(user: JSONObject, keyPassword: String): KeyRing {
        val keys = user.getJSONArray("Keys")
        var ring: KeyRing? = null
        for (i in 0 until keys.length()) {
            val key = runCatching {
                Crypto.newKeyFromArmored(keys.getJSONObject(i).getString("PrivateKey")).unlock(keyPassword.toByteArray())
            }.getOrNull() ?: continue
            ring?.addKey(key) ?: run { ring = Crypto.newKeyRing(key) }
        }
        return checkNotNull(ring) { "No user key could be unlocked" }
    }

    private fun loadAddress(json: JSONObject, userRing: KeyRing, keyPassword: String): Loaded {
        val addressId = json.getString("ID")
        val keys = json.getJSONArray("Keys")
        val unlocked = mutableMapOf<String, ByteArray>()
        val sdkKeys = mutableListOf<Address.Key>()
        var primaryId = ""
        var primaryIndex = 0
        for (j in 0 until keys.length()) {
            val key = keys.getJSONObject(j)
            val keyId = key.getString("ID")
            runCatching { unlocked[keyId] = unlockAddressKey(key, userRing, keyPassword) }
            val flags = key.optInt("Flags", FLAG_ENCRYPT or FLAG_VERIFY)
            sdkKeys += Address.Key(addressId, keyId, key.optInt("Active", 1) == 1, flags and FLAG_ENCRYPT != 0, flags and FLAG_VERIFY != 0)
            if (key.optInt("Primary") == 1) {
                primaryId = keyId
                primaryIndex = j
            }
        }
        val status = when (json.optInt("Status")) {
            STATUS_ENABLED -> Address.Status.ENABLED
            STATUS_DELETING -> Address.Status.DELETING
            else -> Address.Status.DISABLED
        }
        val address = Address(addressId, json.optInt("Order"), json.getString("Email"), status, sdkKeys, primaryIndex)
        return Loaded(address, unlocked, primaryId)
    }

    private fun unlockAddressKey(key: JSONObject, userRing: KeyRing, keyPassword: String): ByteArray {
        val token = key.optString("Token")
        val passphrase = if (key.isNull("Token") || token.isEmpty()) {
            keyPassword.toByteArray()
        } else {
            val plain = userRing.decrypt(Crypto.newPGPMessageFromArmored(token), null, 0)
            userRing.verifyDetached(plain, Crypto.newPGPSignatureFromArmored(key.getString("Signature")), Crypto.getUnixTime())
            plain.data
        }
        return Crypto.newKeyFromArmored(key.getString("PrivateKey")).unlock(passphrase).serialize()
    }

    private fun byId(id: String): Loaded = checkNotNull(loaded).first { it.address.addressId == id }

    override suspend fun getAddress(id: String): Address = byId(id).address

    override suspend fun getDefaultAddress(): Address =
        checkNotNull(loaded).filter { it.address.status == Address.Status.ENABLED }.minBy { it.address.order }.address

    override suspend fun <T> getAddressPrimaryPrivateKey(id: String, block: (ByteArray) -> T): T =
        byId(id).let { block(it.unlocked.getValue(it.primaryKeyId)) }

    override suspend fun <T> getAddressPrivateKeys(id: String, block: (List<ByteArray>) -> T): T =
        block(byId(id).unlocked.values.toList())

    override suspend fun getAddressPublicKeys(emailAddress: String): List<ByteArray> {
        val email = URLEncoder.encode(emailAddress, "UTF-8")
        val body = sessions.call("GET", "/core/v4/keys/all?Email=$email&InternalOnly=1").requireOk("public keys")
        val keys = body.optJSONObject("Address")?.optJSONArray("Keys") ?: return emptyList()
        return (0 until keys.length()).map { Crypto.newKeyFromArmored(keys.getJSONObject(it).getString("PublicKey")).serialize() }
    }

    private fun io.github.joshuagr.neutrino.api.ApiResponse.requireOk(what: String): JSONObject {
        check(ok) { "Could not load $what (HTTP $status, code $code)" }
        return body
    }

    private companion object {
        const val FLAG_VERIFY = 1
        const val FLAG_ENCRYPT = 2
        const val STATUS_ENABLED = 1
        const val STATUS_DELETING = 2
    }
}

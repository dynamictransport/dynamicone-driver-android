package com.dynamictransport.dynamicone.core.security

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.dynamictransport.dynamicone.testing.InMemoryPreferencesDataStore
import com.dynamictransport.dynamicone.testing.InMemoryTokenStore
import com.dynamictransport.dynamicone.testing.TEST_NOW
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenStorageTest {

    /** Stands in for the Android Keystore AES-GCM box; the point here is that nothing is stored in plain text. */
    private class XorCryptoBox : CryptoBox {
        override fun encrypt(plain: ByteArray): ByteArray = ByteArray(plain.size) { (plain[it].toInt() xor 0x5A).toByte() }

        override fun decrypt(data: ByteArray): ByteArray = encrypt(data)
    }

    private val dataStore = InMemoryPreferencesDataStore()

    @Test
    fun `both tokens are stored encrypted as one value and replaced together`() = runBlocking {
        val store = EncryptedTokenStore(dataStore, XorCryptoBox())

        store.write(TokenPair("access-secret-1", "refresh-secret-1", TEST_NOW, TEST_NOW.plusSeconds(86_400)))
        store.write(TokenPair("access-secret-2", "refresh-secret-2", TEST_NOW.plusSeconds(900), null, mustChangePassword = true))

        val raw = dataStore.data.first().asMap()
        assertEquals(1, raw.size)
        assertFalse(raw.values.joinToString().contains("secret"))
        val read = store.read()!!
        assertEquals("access-secret-2", read.accessToken)
        assertEquals("refresh-secret-2", read.refreshToken)
        assertEquals(TEST_NOW.plusSeconds(900), read.accessTokenExpiresAt)
        assertNull(read.refreshTokenExpiresAt)
        assertTrue(read.mustChangePassword)

        store.clear()
        assertNull(store.read())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `unreadable stored tokens end the session instead of crashing`() = runBlocking {
        dataStore.edit {
            it[stringPreferencesKey("driver_api_tokens")] = "not base64 !"
            it[stringPreferencesKey("refresh_token")] = "legacy"
        }
        val store = EncryptedTokenStore(dataStore, XorCryptoBox())

        assertNull(store.read())
        assertTrue(dataStore.data.first().asMap().isEmpty())
    }

    @Test
    fun `token manager rotates the pair and forgets it when the refresh is rejected`() = runBlocking {
        val store = InMemoryTokenStore()
        val manager = TokenManager(store)

        manager.store(TokenPair("access-1", "refresh-1", TEST_NOW, null))
        manager.replaceTokensBlocking(TokenPair("access-2", "refresh-2", TEST_NOW.plusSeconds(900), null))

        assertEquals("access-2", manager.currentAccessToken())
        assertEquals("refresh-2", manager.currentRefreshToken())
        assertEquals("refresh-2", store.tokens?.refreshToken)

        manager.onRefreshRejected()

        assertNull(store.tokens)
        assertNull(manager.currentRefreshToken())
        assertFalse(manager.hasSession())
    }
}

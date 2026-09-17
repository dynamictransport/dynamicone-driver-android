package com.dynamictransport.driver.smoke

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Robolectric has no "AndroidKeyStore" JCA provider. This in-memory stand-in lets the production
 * KeystoreCryptoBox (AES-GCM, alias-based key) run unchanged in JVM smoke tests.
 */
object FakeAndroidKeyStore {

    private const val PROVIDER_NAME = "AndroidKeyStore"
    private val keys = ConcurrentHashMap<String, SecretKey>()

    fun install() {
        if (Security.getProvider(PROVIDER_NAME) == null) Security.addProvider(FakeProvider())
    }

    @Suppress("DEPRECATION")
    private class FakeProvider : Provider(PROVIDER_NAME, 1.0, "In-memory Android Keystore for Robolectric") {
        init {
            put("KeyStore.$PROVIDER_NAME", StoreSpi::class.java.name)
            put("KeyGenerator.AES", GeneratorSpi::class.java.name)
        }
    }

    class StoreSpi : KeyStoreSpi() {
        override fun engineGetKey(alias: String?, password: CharArray?): Key? = alias?.let(keys::get)

        override fun engineGetEntry(alias: String?, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? =
            alias?.let(keys::get)?.let { KeyStore.SecretKeyEntry(it) }

        override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null

        override fun engineGetCertificate(alias: String?): Certificate? = null

        override fun engineGetCreationDate(alias: String?): Date? = null

        override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) {
            if (alias != null && key is SecretKey) keys[alias] = key
        }

        override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) = Unit

        override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = Unit

        override fun engineDeleteEntry(alias: String?) {
            alias?.let(keys::remove)
        }

        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys)

        override fun engineContainsAlias(alias: String?): Boolean = alias != null && keys.containsKey(alias)

        override fun engineSize(): Int = keys.size

        override fun engineIsKeyEntry(alias: String?): Boolean = engineContainsAlias(alias)

        override fun engineIsCertificateEntry(alias: String?): Boolean = false

        override fun engineGetCertificateAlias(cert: Certificate?): String? = null

        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit

        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class GeneratorSpi : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit

        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as? KeyGenParameterSpec)?.keystoreAlias
        }

        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit

        override fun engineGenerateKey(): SecretKey {
            val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            alias?.let { keys[it] = key }
            return key
        }
    }
}

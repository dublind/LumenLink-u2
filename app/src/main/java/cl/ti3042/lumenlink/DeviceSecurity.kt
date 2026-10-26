package cl.ti3042.lumenlink

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import cl.ti3042.lumenlink.core.PinnedTrust
import cl.ti3042.lumenlink.core.fingerprint
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.security.auth.x500.X500Principal

class DeviceSecurity {
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // Solo crea las claves si no existen: conserva el acceso a las lecturas guardadas anteriormente.
    init {
        if (!store.containsAlias("lumenlink-storage")) {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("lumenlink-storage", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
        }
        if (!store.containsAlias("lumenlink-node-tls-v1")) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder("lumenlink-node-tls-v1", KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    // Los mensajes de la aplicación se cifran con TLS, no con RSA directo.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE, KeyProperties.ENCRYPTION_PADDING_RSA_PKCS1)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setKeySize(2048).setCertificateSubject(X500Principal("CN=LumenLink educational node"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(1_577_836_800_000L))
                    .setCertificateNotAfter(Date(2_208_988_800_000L)).build())
            }.generateKeyPair()
        }
    }
    fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey("lumenlink-storage", null) as SecretKey)
        // Guarda el IV junto al texto cifrado para poder descifrar esa lectura después.
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(text: String): String {
        val data = Base64.decode(text, Base64.NO_WRAP)
        require(data.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, store.getKey("lumenlink-storage", null) as SecretKey,
            GCMParameterSpec(128, data.copyOfRange(0, 12)))
        return String(cipher.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8)
    }
    val pin: String get() = fingerprint(store.getCertificate("lumenlink-node-tls-v1") as X509Certificate)
    fun serverContext(): SSLContext {
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
                if (keyType == "RSA") arrayOf("lumenlink-node-tls-v1") else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (keyType == "RSA") "lumenlink-node-tls-v1" else null
            override fun getCertificateChain(alias: String?): Array<X509Certificate> =
                arrayOf(store.getCertificate("lumenlink-node-tls-v1") as X509Certificate)
            override fun getPrivateKey(alias: String?): PrivateKey = store.getKey("lumenlink-node-tls-v1", null) as PrivateKey
        }
        return SSLContext.getInstance("TLSv1.2").apply { init(arrayOf<KeyManager>(keyManager), null, null) }
    }
    // El panel verifica la huella del nodo antes de enviar su token secreto.
    fun clientContext(pin: String): SSLContext = SSLContext.getInstance("TLSv1.2").apply {
        init(null, arrayOf<TrustManager>(PinnedTrust(pin)), null)
    }
}

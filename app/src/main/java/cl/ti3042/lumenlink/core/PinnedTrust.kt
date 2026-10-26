package cl.ti3042.lumenlink.core

import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

fun fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

class PinnedTrust(private val pin: String) : X509TrustManager {
    // La identidad se comprueba con la huella transferida al emparejar, no con una lista de emisores.
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    // El panel usa un token: este protocolo no autentica al cliente con un certificado propio.
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw java.security.cert.CertificateException("No se utiliza autenticación de certificado de cliente")
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("Sin certificado")
        certificate.checkValidity()
        if (!MessageDigest.isEqual(fingerprint(certificate).toByteArray(), pin.toByteArray())) {
            throw java.security.cert.CertificateException("La huella no coincide con el dispositivo autorizado")
        }
    }
}

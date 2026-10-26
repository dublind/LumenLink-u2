package cl.ti3042.lumenlink.core

data class Pairing(val host: String, val port: Int, val fingerprint: String, val token: String) {
    fun encode(): String = "LUMEN1|$host|$port|$fingerprint|$token"
    companion object {
        fun parse(text: String): Pairing {
            val p = text.trim().split('|')
            require(p.size == 5 && p[0] == "LUMEN1") { "Configuración de conexión inválida" }
            val octets = p[1].split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && (it.toIntOrNull() ?: -1) in 0..255 }) { "Dirección IPv4 inválida" }
            val port = p[2].toIntOrNull() ?: error("Puerto inválido")
            require(port in 1024..65535) { "Puerto fuera de rango" }
            require(p[3].matches(Regex("[a-fA-F0-9]{64}"))) { "Huella de certificado inválida" }
            require(p[4].matches(Regex("[a-fA-F0-9]{32}"))) { "Token inválido" }
            return Pairing(p[1], port, p[3].lowercase(), p[4].lowercase())
        }
    }
}

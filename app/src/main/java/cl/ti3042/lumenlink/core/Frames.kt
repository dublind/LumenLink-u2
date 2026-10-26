package cl.ti3042.lumenlink.core

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream

// Cada mensaje lleva su longitud porque TCP puede dividir o juntar los datos.
object Frames {
    const val MAX_BYTES = 256 * 1024
    fun write(out: DataOutputStream, message: JSONObject) {
        val bytes = message.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size in 1..MAX_BYTES) { "Mensaje demasiado grande" }
        out.writeInt(bytes.size)
        out.write(bytes)
        out.flush()
    }
    fun read(input: DataInputStream): JSONObject {
        val size = input.readInt()
        // Rechaza tamaños inválidos antes de reservar memoria.
        require(size in 1..MAX_BYTES) { "Longitud de mensaje inválida" }
        return JSONObject(String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8))
    }
}

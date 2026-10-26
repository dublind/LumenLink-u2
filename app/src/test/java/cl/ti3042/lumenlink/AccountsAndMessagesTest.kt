package cl.ti3042.lumenlink

import cl.ti3042.lumenlink.core.Frames
import cl.ti3042.lumenlink.core.Pairing
import cl.ti3042.lumenlink.core.PasswordHasher
import cl.ti3042.lumenlink.core.Reading
import cl.ti3042.lumenlink.core.fingerprint
import org.json.JSONObject
import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

class AccountsAndMessagesTest {
    @Test fun passwordCanBeVerifiedAndWrongPasswordIsRejected() {
        val salt = PasswordHasher.salt()
        val hash = PasswordHasher.hash("Clave larga de prueba 2026".toCharArray(), salt)
        Assert.assertEquals(32, hash.size)
        Assert.assertTrue(PasswordHasher.matches("Clave larga de prueba 2026".toCharArray(), salt, hash))
        Assert.assertFalse(PasswordHasher.matches("Contraseña incorrecta".toCharArray(), salt, hash))
    }
    @Test fun saltsPreventEqualPasswordHashes() {
        val pass = "Contraseña para prueba".toCharArray()
        Assert.assertFalse(PasswordHasher.hash(pass, PasswordHasher.salt()).contentEquals(
            PasswordHasher.hash(pass, PasswordHasher.salt())))
    }
    @Test fun accountInputHasBounds() {
        Assert.assertFalse(PasswordHasher.validUser("' OR 1=1 --"))
        Assert.assertFalse(PasswordHasher.validUser("ab"))
        Assert.assertTrue(PasswordHasher.validUser("estudiante_01"))
        Assert.assertFalse(PasswordHasher.validPassword("corta"))
        Assert.assertTrue(PasswordHasher.validPassword("seis66"))
        Assert.assertFalse(PasswordHasher.validPassword("a".repeat(129)))
    }
    @Test fun framingHandlesUnicodeAndMultipleMessages() {
        val bytes = ByteArrayOutputStream()
        val out = DataOutputStream(bytes)
        Frames.write(out, JSONObject().put("type", "lectura").put("label", "Distancia ultrasónica cm"))
        Frames.write(out, JSONObject().put("type", "command").put("action", "light_on"))
        val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        Assert.assertEquals("Distancia ultrasónica cm", Frames.read(input).getString("label"))
        Assert.assertEquals("light_on", Frames.read(input).getString("action"))
    }
    @Test fun framingRejectsNegativeAndExcessiveLengths() {
        for (size in listOf(-1, 0, Frames.MAX_BYTES + 1)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).writeInt(size)
            Assert.assertThrows(IllegalArgumentException::class.java) {
                Frames.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
            }
        }
    }
    @Test fun framingRejectsTruncatedFrames() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply { writeInt(100); write(byteArrayOf(1, 2)) }
        Assert.assertThrows(EOFException::class.java) { Frames.read(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }
    private val pair = Pairing("192.168.1.20", 8842, "a".repeat(64), "b".repeat(32))
    @Test fun pairingRoundTrip() { Assert.assertEquals(pair, Pairing.parse(pair.encode())) }
    @Test fun pairingRejectsBadIpPinTokenAndPort() {
        for (value in listOf(pair.copy(host = "999.1.1.1"), pair.copy(port = 80),
            pair.copy(fingerprint = "not-a-pin"), pair.copy(token = "1234"))) {
            Assert.assertThrows(IllegalArgumentException::class.java) { Pairing.parse(value.encode()) }
        }
    }
    @Test fun readingRoundTripPreservesIdentityAndControlState() {
        val r = Reading("sample-001", 1234, 27.5, 80.0, true, false, 30.0, 30.0)
        Assert.assertEquals(r, Reading.parse(r.json()))
    }
    @Test fun readingsRejectUnsafeNumericValues() {
        val r = Reading("id", 1234, 100.0, 80.0, false, true, 30.0, 30.0)
        for (invalid in listOf(r.copy(distanceCm = 1000.0), r.copy(distanceCm = -1.0),
            r.copy(thresholdCm = 201.0), r.copy(ldrPercent = -1.0), r.copy(ldrPercent = 101.0),
            r.copy(ldrThresholdPercent = -1.0), r.copy(id = ""), r.copy(at = -1))) {
            Assert.assertThrows(IllegalArgumentException::class.java) { Reading.parse(invalid.json()) }
        }
    }
}

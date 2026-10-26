package cl.ti3042.lumenlink

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import cl.ti3042.lumenlink.core.PasswordHasher
import cl.ti3042.lumenlink.core.Reading
import org.json.JSONObject

class AppDatabase(context: Context, private val security: DeviceSecurity) : SQLiteOpenHelper(context, "lumenlink.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE users(name TEXT PRIMARY KEY, salt BLOB NOT NULL, hash BLOB NOT NULL, failures INTEGER NOT NULL DEFAULT 0, locked_until INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE readings(owner TEXT NOT NULL, id TEXT NOT NULL, at INTEGER NOT NULL, source TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(owner,id,source))")
        db.execSQL("CREATE INDEX readings_owner_time ON readings(owner,at DESC)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun register(user: String, password: String): String {
        if (!PasswordHasher.validUser(user)) return "Usuario: usa entre 3 y 32 letras, números, punto, guion o guion bajo."
        if (!PasswordHasher.validPassword(password)) return "La contraseña debe tener entre 6 y 128 caracteres."
        val salt = PasswordHasher.salt()
        val chars = password.toCharArray()
        val hash = try { PasswordHasher.hash(chars, salt) } finally { chars.fill('\u0000') }
        val values = ContentValues().apply { put("name", user); put("salt", salt); put("hash", hash) }
        return if (writableDatabase.insertWithOnConflict("users", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L)
            "El usuario ya existe." else "OK"
    }
    @Synchronized fun login(user: String, password: String): Boolean {
        readableDatabase.query("users", null, "name=?", arrayOf(user), null, null, null).use { c ->
            if (!c.moveToFirst()) {
                // También deriva una clave si el usuario no existe para reducir diferencias de tiempo.
                val chars = password.take(128).toCharArray()
                try { PasswordHasher.hash(chars, ByteArray(16)) } finally { chars.fill('\u0000') }
                return false
            }
            val now = System.currentTimeMillis()
            if (c.getLong(c.getColumnIndexOrThrow("locked_until")) > now) return false
            val chars = password.take(128).toCharArray()
            val valid = try { password.length <= 128 && PasswordHasher.matches(chars,
                c.getBlob(c.getColumnIndexOrThrow("salt")), c.getBlob(c.getColumnIndexOrThrow("hash"))) }
                finally { chars.fill('\u0000') }
            val failures = if (valid) 0 else c.getInt(c.getColumnIndexOrThrow("failures")) + 1
            val values = ContentValues().apply {
                put("failures", if (failures >= 5) 0 else failures)
                put("locked_until", if (!valid && failures >= 5) now + 30_000 else 0)
            }
            writableDatabase.update("users", values, "name=?", arrayOf(user))
            return valid
        }
    }
    @Synchronized fun save(user: String, r: Reading, source: String) {
        val values = ContentValues().apply {
            put("owner", user); put("id", r.id); put("at", r.at); put("source", source)
            put("payload", security.encrypt(r.json().toString()))
        }
        // Conserva el ID original para que una sincronización repetida no duplique la lectura.
        writableDatabase.insertWithOnConflict("readings", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        // Conserva las 2.000 lecturas más recientes de cada usuario.
        writableDatabase.execSQL("DELETE FROM readings WHERE owner=? AND rowid NOT IN (SELECT rowid FROM readings WHERE owner=? ORDER BY at DESC LIMIT 2000)", arrayOf(user, user))
    }
    @Synchronized fun list(user: String, source: String? = null, limit: Int = 100, offset: Int = 0): List<Reading> {
        require(limit in 1..100 && offset in 0..2000)
        val where = if (source == null) "owner=?" else "owner=? AND source=?"
        val args = if (source == null) arrayOf(user) else arrayOf(user, source)
        return readableDatabase.query("readings", arrayOf("payload"), where, args, null, null,
            "at DESC, id ASC", "$offset,$limit").use { c ->
            buildList { while (c.moveToNext()) add(Reading.parse(JSONObject(security.decrypt(c.getString(0))))) }
        }
    }
    @Synchronized fun count(user: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM readings WHERE owner=?", arrayOf(user)).use {
        it.moveToFirst(); it.getInt(0)
    }
}

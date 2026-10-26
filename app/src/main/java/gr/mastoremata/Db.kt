package gr.mastoremata

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Βάση SQLite της εφαρμογής.
 * records: όλα τα δεδομένα (εργαλεία, επισκευές, οδηγοί, δεξιότητες, ανταλλακτικά, ρυθμίσεις),
 *          μία γραμμή ανά εγγραφή, με είδος (kind), κλειδί (id), ημερομηνία (day) και JSON.
 * meta:    ρυθμίσεις του εγγενούς κομματιού (φάκελος αντιγράφων κ.λπ.).
 * Οι φωτογραφίες ΔΕΝ μπαίνουν εδώ: είναι αρχεία στο files/photos (Photos.kt) και οι εγγραφές κρατούν το όνομά τους.
 */
class Db(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, NAME, null, VERSION) {

    companion object {
        const val NAME = "mastoremata.db"
        const val VERSION = 1
        @Volatile private var inst: Db? = null
        /** Μία κοινή σύνδεση για όλη την εφαρμογή. */
        @JvmStatic fun get(ctx: Context): Db {
            val cur = inst
            if (cur != null) return cur
            synchronized(this) {
                val again = inst
                if (again != null) return again
                val created = Db(ctx)
                inst = created
                return created
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(kind TEXT NOT NULL, id TEXT NOT NULL, day TEXT, data TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(kind, id))")
        db.execSQL("CREATE INDEX idx_records_day ON records(kind, day)")
        db.execSQL("CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
    }

    /** Όλες οι εγγραφές ως {kind: {id: data}}. */
    fun loadAll(): String {
        val out = JSONObject()
        readableDatabase.rawQuery("SELECT kind, id, data FROM records", null).use { c ->
            while (c.moveToNext()) {
                val kind = c.getString(0)
                var group = out.optJSONObject(kind)
                if (group == null) {
                    group = JSONObject()
                    out.put(kind, group)
                }
                try {
                    group.put(c.getString(1), JSONTokener(c.getString(2)).nextValue())
                } catch (e: Exception) {
                    // κατεστραμμένη εγγραφή: παραλείπεται
                }
            }
        }
        return out.toString()
    }

    /** Εφαρμόζει αλλαγές [{op:'put'|'del', kind, id, day, data}] σε μία συναλλαγή. */
    fun applyOps(ops: String): Boolean {
        val arr = JSONArray(ops)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.getString("kind")
                val id = o.getString("id")
                if (o.optString("op") == "del") {
                    db.delete("records", "kind=? AND id=?", arrayOf(kind, id))
                } else {
                    val cv = ContentValues()
                    cv.put("kind", kind)
                    cv.put("id", id)
                    if (o.isNull("day")) cv.putNull("day") else cv.put("day", o.getString("day"))
                    cv.put("data", o.getString("data"))
                    cv.put("updated", now)
                    db.insertWithOnConflict("records", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            db.setTransactionSuccessful()
            return true
        } catch (e: Exception) {
            return false
        } finally {
            db.endTransaction()
        }
    }

    fun getMeta(key: String): String? {
        readableDatabase.rawQuery("SELECT value FROM meta WHERE key=?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) return c.getString(0)
        }
        return null
    }

    fun setMeta(key: String, value: String) {
        val cv = ContentValues()
        cv.put("key", key)
        cv.put("value", value)
        writableDatabase.insertWithOnConflict("meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }
}

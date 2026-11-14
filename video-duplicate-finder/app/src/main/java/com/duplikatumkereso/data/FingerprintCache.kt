package com.duplikatumkereso.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.duplikatumkereso.core.VideoFingerprint
import java.nio.ByteBuffer

/**
 * Helyi SQLite gyorsítótár: a kiszámolt ujjlenyomatokat és ellenőrző összegeket
 * eltároljuk, így a következő vizsgálat csak az új vagy módosult fájlokat dolgozza fel.
 * A kulcs tartalmazza a méretet és a módosítás idejét, így módosult fájl új kulcsot kap.
 */
class FingerprintCache(context: Context) : SQLiteOpenHelper(context, "ujjlenyomatok.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE video (k TEXT PRIMARY KEY, duration INTEGER, start INTEGER, interval INTEGER, data BLOB)")
        db.execSQL("CREATE TABLE image (k TEXT PRIMARY KEY, hash INTEGER)")
        db.execSQL("CREATE TABLE filehash (k TEXT PRIMARY KEY, partial TEXT, full TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS video")
        db.execSQL("DROP TABLE IF EXISTS image")
        db.execSQL("DROP TABLE IF EXISTS filehash")
        onCreate(db)
    }

    private fun videoKey(item: MediaItem, intervalMs: Long, accurate: Boolean) =
        "${item.cacheKey}/$intervalMs/${if (accurate) "p" else "g"}"

    fun getVideo(item: MediaItem, intervalMs: Long, accurate: Boolean): VideoFingerprint? {
        readableDatabase.rawQuery(
            "SELECT duration, start, interval, data FROM video WHERE k = ?",
            arrayOf(videoKey(item, intervalMs, accurate)),
        ).use { c ->
            if (!c.moveToFirst()) return null
            val data = c.getBlob(3)
            val n = data.size / 9
            val buf = ByteBuffer.wrap(data)
            val hashes = LongArray(n)
            val valid = BooleanArray(n)
            for (i in 0 until n) {
                hashes[i] = buf.getLong()
                valid[i] = buf.get().toInt() != 0
            }
            return VideoFingerprint(item.id, c.getLong(0), c.getLong(1), c.getLong(2), hashes, valid)
        }
    }

    fun putVideo(item: MediaItem, accurate: Boolean, fp: VideoFingerprint) {
        val buf = ByteBuffer.allocate(fp.hashes.size * 9)
        for (i in fp.hashes.indices) {
            buf.putLong(fp.hashes[i])
            buf.put(if (fp.valid[i]) 1 else 0)
        }
        val v = ContentValues().apply {
            put("k", videoKey(item, fp.intervalMs, accurate))
            put("duration", fp.durationMs)
            put("start", fp.startMs)
            put("interval", fp.intervalMs)
            put("data", buf.array())
        }
        writableDatabase.insertWithOnConflict("video", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** null = nincs a tárban; Long.MIN_VALUE = nem olvasható kép */
    fun getImage(item: MediaItem): Long? {
        readableDatabase.rawQuery("SELECT hash FROM image WHERE k = ?", arrayOf(item.cacheKey)).use { c ->
            return if (c.moveToFirst()) c.getLong(0) else null
        }
    }

    fun putImage(item: MediaItem, hash: Long) {
        val v = ContentValues().apply {
            put("k", item.cacheKey)
            put("hash", hash)
        }
        writableDatabase.insertWithOnConflict("image", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getHashes(item: MediaItem): Pair<String?, String?> {
        readableDatabase.rawQuery("SELECT partial, full FROM filehash WHERE k = ?", arrayOf(item.cacheKey)).use { c ->
            return if (c.moveToFirst()) c.getString(0) to c.getString(1) else null to null
        }
    }

    fun putHashes(item: MediaItem, partial: String?, full: String?) {
        val v = ContentValues().apply {
            put("k", item.cacheKey)
            put("partial", partial)
            put("full", full)
        }
        writableDatabase.insertWithOnConflict("filehash", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun clear() {
        writableDatabase.apply {
            delete("video", null, null)
            delete("image", null, null)
            delete("filehash", null, null)
            execSQL("VACUUM")
        }
    }

    fun entryCount(): Long {
        readableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM video) + (SELECT COUNT(*) FROM image)",
            null,
        ).use { c -> return if (c.moveToFirst()) c.getLong(0) else 0 }
    }
}

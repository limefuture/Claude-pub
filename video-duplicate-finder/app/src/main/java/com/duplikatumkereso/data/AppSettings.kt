package com.duplikatumkereso.data

import android.content.Context
import com.duplikatumkereso.core.FolderRef

/** Helyi beállítások (csak a telefonon, mentésből kizárva). */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("beallitasok", Context.MODE_PRIVATE)

    var intervalSec: Int
        get() = prefs.getInt("intervalSec", 10)
        set(v) = prefs.edit().putInt("intervalSec", v).apply()

    var minMatches: Int
        get() = prefs.getInt("minMatches", 10)
        set(v) = prefs.edit().putInt("minMatches", v).apply()

    var frameThreshold: Int
        get() = prefs.getInt("frameThreshold", 10)
        set(v) = prefs.edit().putInt("frameThreshold", v).apply()

    var imageThreshold: Int
        get() = prefs.getInt("imageThreshold", 6)
        set(v) = prefs.edit().putInt("imageThreshold", v).apply()

    var durationToleranceSec: Int
        get() = prefs.getInt("durationToleranceSec", 2)
        set(v) = prefs.edit().putInt("durationToleranceSec", v).apply()

    var accurateFrames: Boolean
        get() = prefs.getBoolean("accurateFrames", false)
        set(v) = prefs.edit().putBoolean("accurateFrames", v).apply()

    var includeSubfolders: Boolean
        get() = prefs.getBoolean("includeSubfolders", true)
        set(v) = prefs.edit().putBoolean("includeSubfolders", v).apply()

    var scanVideos: Boolean
        get() = prefs.getBoolean("scanVideos", true)
        set(v) = prefs.edit().putBoolean("scanVideos", v).apply()

    var scanImages: Boolean
        get() = prefs.getBoolean("scanImages", true)
        set(v) = prefs.edit().putBoolean("scanImages", v).apply()

    var lastFolderDocId: String?
        get() = prefs.getString("lastFolder", null)
        set(v) = prefs.edit().putString("lastFolder", v).apply()

    var lastTreeUri: String?
        get() = prefs.getString("lastTree", null)
        set(v) = prefs.edit().putString("lastTree", v).apply()

    fun lastFolder(): FolderRef? = lastFolderDocId?.let { FolderRef.fromDocumentId(it, lastTreeUri) }
}

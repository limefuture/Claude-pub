package com.duplikatumkereso

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File

/**
 * Ha az app hibával leáll, a hiba leírását helyben elmentjük (csak a telefonon),
 * és a következő indításkor megmutatjuk, hogy a felhasználó továbbíthassa.
 */
class DupApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val text = buildString {
                    appendLine("Duplikátumkereső ${BuildConfigInfo.version(this@DupApp)}")
                    appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Szál: ${thread.name}")
                    appendLine(e.stackTraceToString())
                }
                crashFile(this).writeText(text.take(20_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    companion object {
        fun crashFile(context: Context) = File(context.filesDir, "utolso_hiba.txt")

        /** Az előző futás hibája (ha volt), egyszer adjuk vissza. */
        fun takeLastCrash(context: Context): String? {
            val f = crashFile(context)
            if (!f.exists()) return null
            val text = runCatching { f.readText() }.getOrNull()
            f.delete()
            return text
        }
    }
}

private object BuildConfigInfo {
    fun version(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "?"
}

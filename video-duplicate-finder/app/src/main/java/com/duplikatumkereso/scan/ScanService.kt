package com.duplikatumkereso.scan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.duplikatumkereso.R
import com.duplikatumkereso.data.CrashGuard
import com.duplikatumkereso.data.FingerprintCache
import com.duplikatumkereso.data.MediaRepository
import com.duplikatumkereso.data.ScanProgress
import com.duplikatumkereso.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Előtérszolgáltatás: a vizsgálat akkor is fut, ha az app a háttérbe kerül vagy a képernyő kikapcsol.
 * Megszakítás után az addig kiszámolt ujjlenyomatok megmaradnak, az újraindítás onnan folytatja.
 */
class ScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ScanState.cancel()
            return START_NOT_STICKY
        }
        val options = ScanState.pendingOptions
        createChannel()
        startInForeground(buildNotification("Vizsgálat indul…", 0, 0))
        if (options == null || ScanState.job?.isActive == true) {
            if (ScanState.job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        ScanState.pendingOptions = null
        ScanState.progress.value = ScanProgress.Running("Indítás", 0, 0)

        ScanState.job = scope.launch {
            val cache = FingerprintCache(applicationContext)
            val guard = CrashGuard(applicationContext)
            guard.recoverFromPreviousRun()
            try {
                val result = Scanner(MediaRepository(applicationContext), cache, guard).run(options) { p ->
                    ScanState.progress.value = p
                    notifyProgress(p)
                }
                ScanState.result.value = result
                ScanState.progress.value = ScanProgress.Done
            } catch (e: CancellationException) {
                ScanState.progress.value = ScanProgress.Cancelled
            } catch (e: Throwable) {
                ScanState.progress.value = ScanProgress.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                guard.finish()
                cache.close()
                ServiceCompat.stopForeground(this@ScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: az előtérszolgáltatás napi időkerete lejárt. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ScanState.cancel()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun notifyProgress(p: ScanProgress.Running) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(p.phase, p.done, p.total))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Vizsgálat", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    private fun buildNotification(text: String, done: Int, total: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val content = if (total > 0) "$text · $done / $total" else text
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle("Duplikátumok keresése")
            .setContentText(content)
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Megszakítás", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "vizsgalat"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.duplikatumkereso.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ScanService::class.java))
        }
    }
}

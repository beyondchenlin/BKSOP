package com.wangzan101.mika.workflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.wangzan101.mika.R
import com.wangzan101.mika.ui.MainActivity
import kotlinx.coroutines.*

/** Visible execution lifetime; no model selection, automatic resume or business replay. */
class WorkflowForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var watch: Job? = null
    @Volatile private var latestStartId = 0
    private val servedRuns = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.workflow_notification_title), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, WorkflowForegroundService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        startForeground(NOTIFICATION, Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.workflow_notification_title))
            .setContentText(getString(R.string.workflow_notification_text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.workflow_stop_all), stop).build())
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        intent?.getStringExtra(RUN_ID)?.let { servedRuns.add(it) }
        if (intent?.action == STOP) {
            scope.launch { AndroidWorkflows.get(this@WorkflowForegroundService).stopAll() }
        }
        if (watch?.isActive != true) watch = scope.launch {
            val host = AndroidWorkflows.get(this@WorkflowForegroundService)
            while (isActive) {
                val observedStart = latestStartId
                if (!host.hasActiveRuns() && stopSelfResult(observedStart)) break
                delay(1_000)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // If Android ends the service, stop remaining work. The core keeps uncertain effects in its ledger.
        runCatching { AndroidWorkflows.get(this).stopRuns(servedRuns.toSet()) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        internal const val RUN_ID = "workflow_run_id"
        private const val CHANNEL = "bksop-workflows"
        private const val NOTIFICATION = 2086
        private const val STOP = "com.wangzan101.mika.workflow.STOP"
    }
}

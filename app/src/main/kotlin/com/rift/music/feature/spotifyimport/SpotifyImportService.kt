package com.rift.music.feature.spotifyimport

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
import com.rift.music.R
import com.rift.music.app.MainActivity
import com.rift.music.db.MusicDatabase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground Service for persistent Spotify playlist imports.
 * Keeps large imports (4,000+ tracks) alive across application backgrounding,
 * navigation changes, and device screen locks, while displaying a real-time
 * progress notification with a genuine progress bar and cancel action.
 */
@AndroidEntryPoint
class SpotifyImportService : Service() {

    @Inject
    lateinit var database: MusicDatabase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeImportJob: Job? = null
    private var lastNotifyTimeMs: Long = 0L

    private val notificationManager by lazy {
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL_IMPORT -> {
                Timber.tag(TAG).i("Received ACTION_CANCEL_IMPORT")
                cancelCurrentImport()
                stopForegroundAndSelf()
            }
            ACTION_START_IMPORT -> {
                val link = intent.getStringExtra(EXTRA_LINK)
                if (link.isNullOrBlank()) {
                    Timber.tag(TAG).w("ACTION_START_IMPORT without link, stopping")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startImportExecution(link)
            }
        }
        return START_NOT_STICKY
    }

    private fun startImportExecution(link: String) {
        if (activeImportJob?.isActive == true || SpotifyImportLock.hasAnyActive()) {
            Timber.tag(TAG).w("Import job already running, ignoring duplicate start request")
            return
        }

        val initialProgress = SpotifyImportState(
            status = SpotifyImportStatus.QUEUED,
            currentStage = "Queuing Spotify import...",
            isIndeterminate = true,
        )
        _importState.value = initialProgress

        val foregroundNotification = buildProgressNotification(initialProgress)
        val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                foregroundNotification,
                foregroundServiceType,
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start foreground service for Spotify import")
        }

        activeImportJob = serviceScope.launch {
            try {
                val importer = SpotifyPlaylistImporter(database, this@SpotifyImportService)
                importer.importPlaylist(link) { state ->
                    _importState.value = state
                    updateNotificationThrottled(state)
                }

                val finalState = _importState.value
                val finishMsg = if (finalState.playlistName.isNotBlank()) {
                    "Finished importing \"${finalState.playlistName}\" (${finalState.importedCount} songs)"
                } else {
                    "Imported ${finalState.importedCount} songs"
                }

                val terminalTitle = if (finalState.status == SpotifyImportStatus.COMPLETED) {
                    "Spotify Import Complete"
                } else {
                    "Spotify Import Finished (with errors)"
                }

                showTerminalNotification(terminalTitle, finishMsg)
            } catch (e: CancellationException) {
                Timber.tag(TAG).i("Spotify import cancelled by user")
                _importState.value = _importState.value.copy(
                    status = SpotifyImportStatus.CANCELLED,
                    currentStage = "Import cancelled",
                )
                try {
                    notificationManager.cancel(NOTIFICATION_ID)
                } catch (_: Exception) {}
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Spotify import failed")
                _importState.value = _importState.value.copy(
                    status = SpotifyImportStatus.FAILED,
                    errorMessage = e.message ?: "Failed to import Spotify playlist",
                    currentStage = "Import failed",
                )
                showTerminalNotification("Spotify Import Failed", e.message ?: "Failed to import playlist")
            } finally {
                stopForegroundAndSelf()
            }
        }
    }

    private fun cancelCurrentImport() {
        val active = SpotifyImportCheckpointStore.getActiveCheckpoint(this)
        if (active != null) {
            SpotifyImportCheckpointStore.markCancelled(this, active.importId)
        }
        activeImportJob?.cancel()
        activeImportJob = null
        _importState.value = _importState.value.copy(
            status = SpotifyImportStatus.CANCELLED,
            currentStage = "Import cancelled",
        )
    }

    private fun stopForegroundAndSelf() {
        try {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Spotify Playlist Imports",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shows progress notifications while Spotify playlists are being imported"
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildProgressNotification(state: SpotifyImportState): Notification {
        val cancelIntent = Intent(this, SpotifyImportService::class.java).apply {
            action = ACTION_CANCEL_IMPORT
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            1,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val clickIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SPOTIFY_IMPORT
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val clickPendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = "Importing Spotify playlist"
        val contentText = if (state.totalTracks > 0) {
            val name = state.playlistName.ifBlank { "Playlist" }
            "Importing: $name\n${state.processedTracks} / ${state.totalTracks} tracks (${state.percent}% complete)"
        } else {
            "Importing: ${state.playlistName.ifBlank { "Playlist" }}..."
        }
        val detailText = "${state.importedCount} imported • ${state.skippedCount} skipped • ${state.failedCount} failed"

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rift)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSubText(detailText)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$contentText\n$detailText"))
            .setContentIntent(clickPendingIntent)
            .addAction(R.drawable.close, "Cancel", cancelPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (state.isIndeterminate || state.totalTracks <= 0) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(state.totalTracks, state.processedTracks, false)
        }

        return builder.build()
    }

    private fun updateNotificationThrottled(state: SpotifyImportState, immediate: Boolean = false) {
        val now = System.currentTimeMillis()
        if (immediate || now - lastNotifyTimeMs >= 500L) {
            lastNotifyTimeMs = now
            try {
                notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(state))
            } catch (e: SecurityException) {
                // POST_NOTIFICATIONS permission not granted
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to update Spotify import notification")
            }
        }
    }

    private fun showTerminalNotification(title: String, message: String) {
        val clickIntent = Intent(this, MainActivity::class.java).apply {
            action = ACTION_OPEN_SPOTIFY_IMPORT
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val clickPendingIntent = PendingIntent.getActivity(
            this,
            0,
            clickIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_rift)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(clickPendingIntent)
            .setAutoCancel(true)
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS permission not granted
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to post terminal Spotify import notification")
        }
    }

    companion object {
        private const val TAG = "SpotifyImportService"
        const val CHANNEL_ID = "spotify_import_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START_IMPORT = "com.rift.music.action.START_SPOTIFY_IMPORT"
        const val ACTION_CANCEL_IMPORT = "com.rift.music.action.CANCEL_SPOTIFY_IMPORT"
        const val ACTION_OPEN_SPOTIFY_IMPORT = "com.rift.music.action.OPEN_SPOTIFY_IMPORT"
        const val EXTRA_LINK = "extra_link"

        private val _importState = MutableStateFlow(SpotifyImportState())
        val importState: StateFlow<SpotifyImportState> = _importState.asStateFlow()

        fun start(context: Context, link: String) {
            val intent = Intent(context, SpotifyImportService::class.java).apply {
                action = ACTION_START_IMPORT
                putExtra(EXTRA_LINK, link)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val active = SpotifyImportCheckpointStore.getActiveCheckpoint(context)
            if (active != null) {
                SpotifyImportCheckpointStore.markCancelled(context, active.importId)
            }
            val intent = Intent(context, SpotifyImportService::class.java).apply {
                action = ACTION_CANCEL_IMPORT
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to send cancel intent to SpotifyImportService")
            }
        }

        fun restoreInterruptedCheckpoint(checkpoint: SpotifyImportCheckpoint) {
            _importState.value = checkpoint.toState().copy(
                status = SpotifyImportStatus.PAUSED_RETRYING,
                currentStage = "Interrupted: ${checkpoint.processedCount}/${checkpoint.totalTracks} tracks saved"
            )
        }

        fun resetState() {
            _importState.value = SpotifyImportState()
        }
    }
}

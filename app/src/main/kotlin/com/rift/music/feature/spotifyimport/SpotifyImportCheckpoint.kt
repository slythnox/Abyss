package com.rift.music.feature.spotifyimport

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File

/**
 * Durable persistence checkpoint for Spotify playlist imports.
 * Survives process death, device reboots, and background termination.
 */
@Serializable
data class SpotifyImportCheckpoint(
    val importId: String,
    val playlistUrl: String,
    val playlistName: String,
    val artworkUrl: String? = null,
    val totalTracks: Int = 0,
    val nextOffset: Int = 0,
    val lastBatchIndex: Int = 0,
    val importedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val status: SpotifyImportStatus = SpotifyImportStatus.IDLE,
    val isCancelled: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
) {
    val processedCount: Int
        get() = importedCount + skippedCount + failedCount

    val progress: Float
        get() = if (totalTracks > 0) (processedCount.toFloat() / totalTracks).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = (progress * 100).toInt()

    fun toJsonString(): String {
        return jsonSerializer.encodeToString(this)
    }

    fun toState(): SpotifyImportState {
        return SpotifyImportState(
            status = status,
            playlistId = importId,
            playlistName = playlistName,
            artworkUrl = artworkUrl,
            totalTracks = totalTracks,
            processedTracks = processedCount,
            importedCount = importedCount,
            skippedCount = skippedCount,
            failedCount = failedCount,
            currentStage = when (status) {
                SpotifyImportStatus.COMPLETED -> "Import complete"
                SpotifyImportStatus.COMPLETED_WITH_ERRORS -> "Import completed with $failedCount unmatchable tracks"
                SpotifyImportStatus.CANCELLED -> "Import cancelled"
                SpotifyImportStatus.FAILED -> "Import failed"
                SpotifyImportStatus.PROCESSING -> "Importing tracks ($processedCount / $totalTracks)"
                else -> "Paused / Interrupted ($processedCount / $totalTracks)"
            },
            isIndeterminate = totalTracks <= 0,
        )
    }

    companion object {
        const val CHECKPOINT_PREFIX = "SPOTIFY_CHECKPOINT_V1:"

        private val jsonSerializer = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        fun fromJsonString(jsonStr: String): SpotifyImportCheckpoint? {
            return try {
                val clean = if (jsonStr.startsWith(CHECKPOINT_PREFIX)) {
                    jsonStr.removePrefix(CHECKPOINT_PREFIX)
                } else {
                    jsonStr
                }
                jsonSerializer.decodeFromString<SpotifyImportCheckpoint>(clean)
            } catch (e: Exception) {
                Timber.tag("SpotifyCheckpoint").w(e, "Failed to parse checkpoint JSON")
                null
            }
        }
    }
}

/**
 * Manages atomic disk-backed and memory-synchronized storage of Spotify import checkpoints.
 */
object SpotifyImportCheckpointStore {
    private const val TAG = "SpotifyCheckpointStore"
    private const val DIR_NAME = "spotify_import_checkpoints"
    private const val ACTIVE_FILE = "active_import_checkpoint.json"

    private val lock = Any()

    private fun getStorageDir(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveCheckpoint(context: Context, checkpoint: SpotifyImportCheckpoint) {
        synchronized(lock) {
            try {
                val dir = getStorageDir(context)
                val activeFile = File(dir, ACTIVE_FILE)
                val playlistFile = File(dir, "${checkpoint.importId}.json")
                val json = checkpoint.toJsonString()

                // Atomic write via temp file
                val tempFile = File(dir, "temp_${System.currentTimeMillis()}.tmp")
                tempFile.writeText(json, Charsets.UTF_8)

                if (checkpoint.status == SpotifyImportStatus.COMPLETED ||
                    checkpoint.status == SpotifyImportStatus.COMPLETED_WITH_ERRORS ||
                    checkpoint.isCancelled
                ) {
                    if (activeFile.exists()) {
                        activeFile.delete()
                    }
                } else {
                    tempFile.copyTo(activeFile, overwrite = true)
                }

                tempFile.copyTo(playlistFile, overwrite = true)
                tempFile.delete()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to save Spotify import checkpoint")
            }
        }
    }

    fun getActiveCheckpoint(context: Context): SpotifyImportCheckpoint? {
        synchronized(lock) {
            try {
                val dir = getStorageDir(context)
                val activeFile = File(dir, ACTIVE_FILE)
                if (activeFile.exists() && activeFile.length() > 0) {
                    val content = activeFile.readText(Charsets.UTF_8)
                    val checkpoint = SpotifyImportCheckpoint.fromJsonString(content)
                    if (checkpoint != null && !checkpoint.isCancelled &&
                        checkpoint.status != SpotifyImportStatus.COMPLETED &&
                        checkpoint.status != SpotifyImportStatus.COMPLETED_WITH_ERRORS
                    ) {
                        return checkpoint
                    }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read active Spotify import checkpoint")
            }
            return null
        }
    }

    fun getCheckpointForPlaylist(context: Context, playlistId: String): SpotifyImportCheckpoint? {
        synchronized(lock) {
            try {
                val dir = getStorageDir(context)
                val playlistFile = File(dir, "$playlistId.json")
                if (playlistFile.exists() && playlistFile.length() > 0) {
                    val content = playlistFile.readText(Charsets.UTF_8)
                    return SpotifyImportCheckpoint.fromJsonString(content)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to read checkpoint for $playlistId")
            }
            return null
        }
    }

    fun markCancelled(context: Context, importId: String) {
        synchronized(lock) {
            try {
                val existing = getCheckpointForPlaylist(context, importId) ?: getActiveCheckpoint(context)
                if (existing != null) {
                    val cancelled = existing.copy(
                        status = SpotifyImportStatus.CANCELLED,
                        isCancelled = true,
                        timestampMs = System.currentTimeMillis()
                    )
                    saveCheckpoint(context, cancelled)
                }
                val dir = getStorageDir(context)
                val activeFile = File(dir, ACTIVE_FILE)
                if (activeFile.exists()) {
                    activeFile.delete()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to mark import $importId as cancelled")
            }
        }
    }

    fun clearActive(context: Context) {
        synchronized(lock) {
            try {
                val dir = getStorageDir(context)
                val activeFile = File(dir, ACTIVE_FILE)
                if (activeFile.exists()) {
                    activeFile.delete()
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to clear active import file")
            }
        }
    }
}

package com.rift.music.feature.spotifyimport

/**
 * Process-wide lock manager ensuring only ONE playlist import can run
 * across the entire application at any given time.
 */
object SpotifyImportLock {
    private val lock = Any()

    @Volatile
    private var activeImportId: String? = null

    fun tryAcquire(importId: String): Boolean {
        synchronized(lock) {
            if (activeImportId == null) {
                activeImportId = importId
                return true
            }
            return false
        }
    }

    fun release(importId: String) {
        synchronized(lock) {
            if (activeImportId == importId) {
                activeImportId = null
            }
        }
    }

    fun isRunning(importId: String): Boolean = activeImportId == importId

    fun hasAnyActive(): Boolean = activeImportId != null

    fun getActiveImportId(): String? = activeImportId

    fun clearAll() {
        synchronized(lock) {
            activeImportId = null
        }
    }
}

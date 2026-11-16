package com.rift.music.feature.spotifyimport

/**
 * Coherent lifecycle and progress state for Spotify playlist imports.
 * Shared across the background foreground service, ViewModel, and UI.
 */
enum class SpotifyImportStatus {
    IDLE,
    QUEUED,
    FETCHING_PLAYLIST,
    PROCESSING,
    PAUSED_RETRYING,
    COMPLETED,
    COMPLETED_WITH_ERRORS,
    CANCELLED,
    FAILED
}

data class SpotifyImportState(
    val status: SpotifyImportStatus = SpotifyImportStatus.IDLE,
    val playlistId: String = "",
    val playlistName: String = "",
    val artworkUrl: String? = null,
    val totalTracks: Int = 0,
    val processedTracks: Int = 0,
    val importedCount: Int = 0,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val currentStage: String = "",
    val errorMessage: String? = null,
    val isIndeterminate: Boolean = false,
) {
    val progress: Float
        get() = if (totalTracks > 0) (processedTracks.toFloat() / totalTracks).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = (progress * 100).toInt()

    val isActive: Boolean
        get() = status == SpotifyImportStatus.QUEUED ||
                status == SpotifyImportStatus.FETCHING_PLAYLIST ||
                status == SpotifyImportStatus.PROCESSING ||
                status == SpotifyImportStatus.PAUSED_RETRYING

    val isFinished: Boolean
        get() = status == SpotifyImportStatus.COMPLETED ||
                status == SpotifyImportStatus.COMPLETED_WITH_ERRORS ||
                status == SpotifyImportStatus.CANCELLED ||
                status == SpotifyImportStatus.FAILED
}

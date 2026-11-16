package com.rift.music.feature.spotifyimport

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rift.music.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class SpotifyImportUiState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class SpotifyImportProgress(
    val playlistName: String,
    val currentSongIndex: Int,
    val totalSongs: Int,
    val percent: Float,
    val isFinished: Boolean = false,
    val importedCount: Int = currentSongIndex,
    val skippedCount: Int = 0,
    val failedCount: Int = 0,
    val currentStage: String = "",
    val artworkUrl: String? = null,
)

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpotifyImportUiState(isLoading = false))
    val uiState: StateFlow<SpotifyImportUiState> = _uiState.asStateFlow()

    val importState: StateFlow<SpotifyImportState> = SpotifyImportService.importState

    val importProgress: StateFlow<SpotifyImportProgress?> = importState.map { state ->
        if (state.status == SpotifyImportStatus.IDLE) {
            null
        } else {
            SpotifyImportProgress(
                playlistName = state.playlistName,
                currentSongIndex = state.processedTracks,
                totalSongs = state.totalTracks,
                percent = state.progress,
                isFinished = state.isFinished,
                importedCount = state.importedCount,
                skippedCount = state.skippedCount,
                failedCount = state.failedCount,
                currentStage = state.currentStage,
                artworkUrl = state.artworkUrl,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    init {
        checkAndRestoreCheckpoint()
    }

    private fun checkAndRestoreCheckpoint() {
        if (SpotifyImportService.importState.value.status == SpotifyImportStatus.IDLE) {
            val checkpoint = SpotifyImportCheckpointStore.getActiveCheckpoint(context)
            if (checkpoint != null && !checkpoint.isCancelled &&
                checkpoint.status != SpotifyImportStatus.COMPLETED &&
                checkpoint.status != SpotifyImportStatus.COMPLETED_WITH_ERRORS
            ) {
                SpotifyImportService.restoreInterruptedCheckpoint(checkpoint)
                _globalShowDialog.value = true
            }
        }
    }

    val showDialog: StateFlow<Boolean> = _globalShowDialog.asStateFlow()
    val isBackgrounded: StateFlow<Boolean> = _globalIsBackgrounded.asStateFlow()

    fun doInBackground() {
        _globalIsBackgrounded.value = true
        _globalShowDialog.value = false
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                context.getString(R.string.spotify_import_running_in_background),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    fun resumeInterruptedImport() {
        val checkpoint = SpotifyImportCheckpointStore.getActiveCheckpoint(context)
        if (checkpoint != null && !checkpoint.isCancelled) {
            _globalShowDialog.value = true
            _globalIsBackgrounded.value = false
            _uiState.update { it.copy(isLoading = false, errorMessage = null) }
            SpotifyImportService.start(context, checkpoint.playlistUrl)
        }
    }

    fun cancelImport() {
        SpotifyImportService.cancel(context)
        _globalShowDialog.value = false
        _globalIsBackgrounded.value = false
        _uiState.update { it.copy(isLoading = false) }
    }

    fun dismissImportProgress() {
        _globalShowDialog.value = false
        _globalIsBackgrounded.value = false
        SpotifyImportService.resetState()
    }

    fun dismissError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun importPlaylistByLink(link: String) {
        if (importState.value.isActive || SpotifyImportLock.hasAnyActive()) {
            Toast.makeText(
                context,
                "A Spotify import is already in progress. Only one playlist can be imported at a time.",
                Toast.LENGTH_LONG
            ).show()
            _globalShowDialog.value = true
            return
        }

        _globalShowDialog.value = true
        _globalIsBackgrounded.value = false
        _uiState.update { it.copy(isLoading = false, errorMessage = null) }

        SpotifyImportService.start(context, link)
    }

    companion object {
        private val _globalShowDialog = MutableStateFlow(false)
        private val _globalIsBackgrounded = MutableStateFlow(false)

        fun showImportDialog() {
            _globalShowDialog.value = true
            _globalIsBackgrounded.value = false
        }
    }
}

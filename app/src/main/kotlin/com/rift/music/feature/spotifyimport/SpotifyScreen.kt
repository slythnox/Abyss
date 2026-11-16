package com.rift.music.feature.spotifyimport

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.rift.music.LocalPlayerAwareWindowInsets
import com.rift.music.R
import com.rift.music.constants.RecommendationEngine
import com.rift.music.constants.RecommendationEngineKey
import com.rift.music.ui.component.DefaultDialog
import com.rift.music.ui.component.GlassSwitchCompat as Switch
import com.rift.music.ui.component.IconButton
import com.rift.music.ui.component.Material3SettingsGroup
import com.rift.music.ui.component.Material3SettingsItem
import com.rift.music.ui.utils.appTopBarWindowInsets
import com.rift.music.ui.utils.backToMain
import com.rift.music.utils.rememberEnumPreference

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: SpotifyImportViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var playlistLink by rememberSaveable { mutableStateOf("") }
    val (recommendationEngine, onRecommendationEngineChange) = rememberEnumPreference(
        key = RecommendationEngineKey,
        defaultValue = RecommendationEngine.YOUTUBE
    )
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val showDialog by viewModel.showDialog.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        Text(
            text = stringResource(R.string.spotify),
            style = MaterialTheme.typography.displaySmall.copy(
                fontWeight = FontWeight.SemiBold
            ),
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp, top = 24.dp, bottom = 16.dp)
        )

        // Spotify is a public metadata source; account login is intentionally not supported.
        Material3SettingsGroup(
            title = "Public metadata import",
            items = listOf(
                Material3SettingsItem(
                    title = { Text("No Spotify login required") },
                    description = { Text("Paste a public playlist link below. Tracks are matched to YouTube Music for playback.") },
                    icon = painterResource(R.drawable.spotify),
                    onClick = {}
                )
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Live Active Import Card visible directly on the page
        if (importState.isActive || importState.status == SpotifyImportStatus.PAUSED_RETRYING) {
            val isInterrupted = importState.status == SpotifyImportStatus.PAUSED_RETRYING
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = if (isInterrupted) {
                    MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                },
                tonalElevation = 4.dp,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!importState.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = importState.artworkUrl,
                                contentDescription = "Playlist Cover",
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.spotify),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(36.dp)
                                    .padding(4.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = importState.playlistName.ifBlank { "Spotify Playlist" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                            )
                            Text(
                                text = if (isInterrupted) "Import Paused / Interrupted" else importState.currentStage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }

                        Text(
                            text = "${importState.percent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    if (importState.isIndeterminate) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { importState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${importState.importedCount} imported • ${importState.failedCount} failed • ${importState.processedTracks}/${importState.totalTracks}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (isInterrupted) {
                                Button(
                                    onClick = { viewModel.resumeInterruptedImport() },
                                    shape = CircleShape,
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("Resume", style = MaterialTheme.typography.labelMedium)
                                }
                            } else {
                                TextButton(
                                    onClick = { SpotifyImportViewModel.showImportDialog() },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Details", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            TextButton(
                                onClick = { viewModel.cancelImport() },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text("Cancel", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Material3SettingsGroup(
            title = "Import via Playlist Link",
            items = listOf(
                Material3SettingsItem(
                    title = {
                        OutlinedTextField(
                            value = playlistLink,
                            onValueChange = { playlistLink = it },
                            label = { Text("Spotify Playlist Link or ID") },
                            placeholder = { Text(if (importState.isActive) "An import is currently running..." else "https://open.spotify.com/playlist/...") },
                            singleLine = true,
                            enabled = !importState.isActive,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            trailingIcon = {
                                if (playlistLink.isNotEmpty() && !importState.isActive) {
                                    IconButton(onClick = { playlistLink = "" }) {
                                        Icon(painterResource(R.drawable.close), contentDescription = "Clear")
                                    }
                                }
                            }
                        )
                    },
                    icon = painterResource(R.drawable.link),
                    onClick = {}
                )
            )
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (playlistLink.isBlank()) {
                    android.widget.Toast.makeText(context, "Please enter a Spotify link or ID first", android.widget.Toast.LENGTH_SHORT).show()
                    return@Button
                }
                viewModel.importPlaylistByLink(playlistLink)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !state.isLoading && !importState.isActive && playlistLink.isNotBlank(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
        ) {
            if (importState.isActive) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Importing (${importState.percent}%)...",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            } else if (state.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Importing...",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            } else {
                Text(
                    text = "Import Playlist",
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Material3SettingsGroup(
            title = "Music Recommendations",
            items = listOf(
                Material3SettingsItem(
                    title = { Text("Use Spotify Recommendations") },
                    description = {
                        Text("Power Daily Discover, Quick Picks & Radio with Spotify recommendation algorithm")
                    },
                    icon = painterResource(R.drawable.spotify),
                    trailingContent = {
                        Switch(
                            checked = recommendationEngine == RecommendationEngine.SPOTIFY,
                            onCheckedChange = { isChecked ->
                                onRecommendationEngineChange(
                                    if (isChecked) RecommendationEngine.SPOTIFY else RecommendationEngine.YOUTUBE
                                )
                            }
                        )
                    },
                    onClick = {
                        onRecommendationEngineChange(
                            if (recommendationEngine == RecommendationEngine.SPOTIFY) RecommendationEngine.YOUTUBE else RecommendationEngine.SPOTIFY
                        )
                    }
                )
            )
        )

        // Info block
        Row(
            modifier = Modifier.padding(top = 24.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                painter = painterResource(R.drawable.info),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = stringResource(R.string.spotify_import_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(36.dp))
    }

    TopAppBar(
            windowInsets = appTopBarWindowInsets(),
        title = {},
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent
        )
    )

    if (showDialog) {
        importProgress?.let { progress ->
            val isFinished = progress.isFinished || progress.percent >= 1f
            val isInterrupted = importState.status == SpotifyImportStatus.PAUSED_RETRYING
            DefaultDialog(
                onDismiss = {
                    if (isFinished) {
                        viewModel.dismissImportProgress()
                    } else {
                        viewModel.cancelImport()
                    }
                },
                title = {
                    Text(
                        text = when {
                            isFinished -> stringResource(R.string.spotify_import_complete)
                            isInterrupted -> "Resume Interrupted Import?"
                            else -> stringResource(R.string.spotify_import_in_progress)
                        }
                    )
                },
                buttons = {
                    if (isFinished) {
                        Button(
                            onClick = { viewModel.dismissImportProgress() },
                            shape = CircleShape
                        ) {
                            Text(stringResource(android.R.string.ok))
                        }
                    } else if (isInterrupted) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.resumeInterruptedImport() },
                                shape = CircleShape
                            ) {
                                Text("Resume")
                            }
                            TextButton(onClick = { viewModel.cancelImport() }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { viewModel.doInBackground() }) {
                                Text(stringResource(R.string.do_in_background))
                            }
                            TextButton(onClick = { viewModel.cancelImport() }) {
                                Text(stringResource(android.R.string.cancel))
                            }
                        }
                    }
                }
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (!progress.artworkUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = progress.artworkUrl,
                                contentDescription = "Playlist Cover",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop,
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = progress.playlistName.ifBlank { stringResource(R.string.playlists) },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            if (progress.currentStage.isNotBlank()) {
                                Text(
                                    text = progress.currentStage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "${progress.currentSongIndex} / ${progress.totalSongs} tracks",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${(progress.percent * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }

                    LinearProgressIndicator(
                        progress = { progress.percent },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )

                    Text(
                        text = "${progress.importedCount} imported • ${progress.skippedCount} skipped • ${progress.failedCount} failed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    state.errorMessage?.let { error ->
        DefaultDialog(
            onDismiss = { viewModel.dismissError() },
            title = { Text("Error") },
            buttons = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text(stringResource(android.R.string.ok))
                }
            }
        ) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


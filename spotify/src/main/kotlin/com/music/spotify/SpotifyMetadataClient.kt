package com.music.spotify

import com.music.spotify.models.SpotifyTrack
import kotlinx.coroutines.flow.Flow

/** Public, metadata-only boundary used by playlist import. */
interface SpotifyMetadataClient {
    suspend fun getPlaylistMetadata(playlistId: String): Result<SpotifyPlaylistMetadata>
    suspend fun getTrackMetadata(trackId: String): Result<SpotifyTrack>
    fun getPlaylistTracksPaged(
        playlistId: String,
        startOffset: Int = 0,
        pageSize: Int = 100,
        maxTracks: Int = 10_000,
    ): Flow<Spotify.SpotifyPagedBatch> = clientDefaultPaged(playlistId, startOffset, pageSize, maxTracks)

    private fun clientDefaultPaged(
        playlistId: String,
        startOffset: Int,
        pageSize: Int,
        maxTracks: Int,
    ): Flow<Spotify.SpotifyPagedBatch> = Spotify.fetchPlaylistPagedTracks(playlistId, startOffset, pageSize, maxTracks)
}

data class SpotifyPlaylistMetadata(
    val id: String,
    val name: String,
    val tracks: List<SpotifyTrack>,
    val imageUrl: String? = null,
    val totalTracks: Int = tracks.size,
)

/** Adapter for the existing public Spotify embed parser. No account state is used. */
class PublicSpotifyMetadataClient(
    private val client: Spotify = Spotify,
) : SpotifyMetadataClient {
    override suspend fun getPlaylistMetadata(playlistId: String): Result<SpotifyPlaylistMetadata> =
        client.fetchPlaylistMetadata(playlistId)

    override suspend fun getTrackMetadata(trackId: String): Result<SpotifyTrack> =
        Result.failure(UnsupportedOperationException(
            "Public track metadata lookup is not exposed by the current SPClient; use playlist metadata or add it to this adapter."
        ))

    override fun getPlaylistTracksPaged(
        playlistId: String,
        startOffset: Int,
        pageSize: Int,
        maxTracks: Int,
    ): Flow<Spotify.SpotifyPagedBatch> = client.fetchPlaylistPagedTracks(playlistId, startOffset, pageSize, maxTracks)
}

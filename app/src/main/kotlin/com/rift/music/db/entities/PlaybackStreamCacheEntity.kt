/**
 * Napster (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.rift.music.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Persistent playback stream cache entry storing resolved stream URLs,
 * their known or estimated expiry times, and audio format metadata.
 * Does NOT persist authentication credentials, cookies, or private tokens.
 */
@Immutable
@Entity(
    tableName = "playback_stream_cache",
    indices = [
        Index(value = ["trackId"]),
        Index(value = ["expiresAtMs"]),
        Index(value = ["isValid"])
    ]
)
data class PlaybackStreamCacheEntity(
    /**
     * Cache key combining track ID, provider, and effective quality.
     * e.g. "dQw4w9WgXcQ:youtube:HIGH" or "dQw4w9WgXcQ#flac:tidal:LOSSLESS".
     */
    @PrimaryKey
    val cacheKey: String,

    /**
     * Target playable track identifier (e.g. YouTube video ID).
     */
    val trackId: String,

    /**
     * Provider originating the stream (e.g. "youtube", "tidal", "jiosaavn", "spine", "addon").
     */
    val provider: String,

    /**
     * Audio quality configuration (e.g. "LOW", "MEDIUM", "HIGH", "LOSSLESS").
     */
    val quality: String,

    /**
     * Resolved stream playback URL.
     */
    val streamUrl: String,

    /**
     * Timestamp (epoch ms) after which this stream URL is considered expired.
     */
    val expiresAtMs: Long,

    /**
     * Timestamp (epoch ms) when this stream URL was resolved.
     */
    val resolvedAtMs: Long = System.currentTimeMillis(),

    /**
     * MIME type (e.g. "audio/webm", "audio/mp4", "audio/flac").
     */
    val formatMimeType: String? = null,

    /**
     * Bitrate in bps.
     */
    val bitrate: Int = 0,

    /**
     * Audio sample rate in Hz.
     */
    val sampleRate: Int? = null,

    /**
     * Content length in bytes.
     */
    val contentLength: Long = 0L,

    /**
     * Whether this cache entry is valid or has been invalidated (e.g. on HTTP 403/404/416).
     */
    val isValid: Boolean = true,
)

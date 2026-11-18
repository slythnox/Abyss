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
 * Persistent identity mapping connecting an external source track (e.g. Spotify track ID)
 * to a verified provider playback entity (e.g. YouTube video ID, Tidal track, etc.).
 */
@Immutable
@Entity(
    tableName = "provider_mapping",
    indices = [
        Index(value = ["providerTrackId"]),
        Index(value = ["sourceProvider", "provider"])
    ]
)
data class ProviderMappingEntity(
    /**
     * Source track identifier (e.g. Spotify track ID "4cOdK2wGLETKBW3PvgPWqT" or "spotify:track:...").
     */
    @PrimaryKey
    val sourceId: String,

    /**
     * Provider originating the source metadata (e.g. "spotify", "isrc", "local").
     */
    val sourceProvider: String,

    /**
     * Target playable provider track identifier (e.g. YouTube video ID "dQw4w9WgXcQ").
     */
    val providerTrackId: String,

    /**
     * Target provider name (e.g. "youtube", "tidal", "jiosaavn", "spine").
     */
    val provider: String,

    /**
     * Match confidence score from 0.0 (unverified) to 1.0 (exact match).
     */
    val confidence: Float,

    /**
     * Match status: MATCHED, UNCERTAIN, FAILED, MANUAL.
     */
    val matchStatus: String,

    /**
     * Timestamp (epoch ms) when this mapping was verified.
     */
    val verifiedAt: Long = System.currentTimeMillis(),

    /**
     * Optional reason if this mapping was invalidated or rejected.
     */
    val invalidationReason: String? = null,
)

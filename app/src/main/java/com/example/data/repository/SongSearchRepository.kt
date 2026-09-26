package com.example.data.repository

import com.example.data.model.Song
import com.example.data.remote.JioSaavnApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class TrackResult(
    val id: String,
    val title: String,
    val artist: String,
    val albumTitle: String,
    val albumArtUrl: String?,
    val streamUrl320kbps: String,
    val durationMs: Long
)

class SongSearchRepository(
    private val apiService: JioSaavnApiService = JioSaavnApiService.create()
) {

    /**
     * Searches JioSaavn API and returns parsed TrackResults with direct 320kbps audio URLs.
     */
    suspend fun searchTracks(query: String): Result<List<TrackResult>> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext Result.success(emptyList())

        try {
            val response = apiService.searchSongs(query = query)
            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("JioSaavn API error: HTTP ${response.code()} ${response.message()}")
                )
            }

            val rawSongs = response.body()?.data?.results.orEmpty()
            val tracks = rawSongs.mapNotNull { item ->
                val streamUrl = item.get320KbpsStreamingUrl() ?: return@mapNotNull null

                TrackResult(
                    id = item.id,
                    title = item.title
                        .replace("&quot;", "\"")
                        .replace("&#039;", "'")
                        .replace("&amp;", "&"),
                    artist = item.artists?.primary?.mapNotNull { it.name }?.joinToString(", ")
                        ?.replace("&amp;", "&")
                        ?.replace("&#039;", "'")
                        ?.ifBlank { "Unknown Artist" } ?: "Unknown Artist",
                    albumTitle = item.album?.name ?: "",
                    albumArtUrl = item.getHighResAlbumArt(),
                    streamUrl320kbps = streamUrl,
                    durationMs = (item.durationSeconds ?: 0) * 1000L
                )
            }

            Result.success(tracks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Resolves the top direct 320kbps streaming URL for a given song query.
     */
    suspend fun getTopStreamUrl(songQuery: String): String? = withContext(Dispatchers.IO) {
        searchTracks(songQuery).getOrNull()?.firstOrNull()?.streamUrl320kbps
    }

    /**
     * Helper that returns full domain Song models ready for the player & UI.
     */
    suspend fun searchDomainSongs(query: String): List<Song> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val response = apiService.searchSongs(query = query)
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.data?.results.orEmpty().mapNotNull { it.toDomainSong() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches similar songs for a given JioSaavn song ID.
     */
    suspend fun getSimilarDomainSongs(songId: String): List<Song> = withContext(Dispatchers.IO) {
        if (songId.isBlank()) return@withContext emptyList()
        try {
            // Strip "saavn_" prefix if present
            val cleanId = songId.removePrefix("saavn_")
            val response = apiService.getSimilarSongs(id = cleanId)
            if (!response.isSuccessful) return@withContext emptyList()

            response.body()?.data.orEmpty().mapNotNull { it.toDomainSong() }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

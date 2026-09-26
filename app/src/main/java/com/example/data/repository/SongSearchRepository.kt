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
    private val apiService: JioSaavnApiService = JioSaavnApiService.create(),
    private val pipedApiService: com.example.data.remote.PipedApiService = com.example.data.remote.PipedApiService.create()
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

            response.body()?.data?.results.orEmpty()
                .mapNotNull { it.toDomainSong() }
                .applyAggressiveQualityFilter()
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

            // Shuffle the results to avoid echo chamber loop, and aggressively filter fakes
            response.body()?.data.orEmpty()
                .mapNotNull { it.toDomainSong() }
                .applyAggressiveQualityFilter()
                .shuffled()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches YouTube recommendations and translates them to JioSaavn songs with strict blacklist checking.
     */
    suspend fun getYouTubeRecommendations(
        currentTitle: String, 
        currentArtist: String,
        blacklistTitles: Set<String>
    ): List<Song> = withContext(Dispatchers.IO) {
        try {
            val pipedResponse = pipedApiService.searchYouTube("$currentTitle $currentArtist")
            if (!pipedResponse.isSuccessful) return@withContext emptyList()

            // 1. Fetch at least 15-20 results and shuffle them
            val topItems = pipedResponse.body()?.items?.filter { it.type == "stream" }?.take(20)?.shuffled().orEmpty()
            
            val recommendedSongs = mutableListOf<Song>()
            val localBlacklist = blacklistTitles.toMutableSet()
            for (item in topItems) {
                // 3. Pick 2-3 completely unique tracks
                if (recommendedSongs.size >= 3) break
                
                val cleanPipedTitle = item.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim().lowercase()
                
                // 2. History Blacklist checking
                if (localBlacklist.any { cleanPipedTitle.contains(it) || it.contains(cleanPipedTitle) }) {
                    continue
                }

                // Search JioSaavn silently for the recommended title
                val saavnResults = searchDomainSongs(item.title)
                
                val validSong = saavnResults.firstOrNull { saavnSong ->
                    val cleanSaavnTitle = saavnSong.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim().lowercase()
                    !localBlacklist.contains(cleanSaavnTitle)
                }
                
                validSong?.let { 
                    recommendedSongs.add(it)
                    localBlacklist.add(it.title.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim().lowercase())
                }
            }
            recommendedSongs
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * AGGRESSIVE QUALITY FILTER
     * Drops fake tracks, generic EDM loops, and remixes based on keywords, duration, and album names.
     */
    private fun List<Song>.applyAggressiveQualityFilter(): List<Song> {
        val spamKeywords = listOf(
            "remix", "dj", "instrumental", "cover", "lofi", "slowed", "reverb", "8d", 
            "mashup", "bgm", "karaoke", "version", "mix", "soundify", "trance", "techno", "bass boosted"
        )
        val spamAlbums = listOf("hot hits", "happy vibes")

        return this.filter { song ->
            val title = song.title.lowercase()
            val artist = song.artist.lowercase()
            val album = song.album.lowercase()

            // 1. RUTHLESS KEYWORD BLACKLIST
            val hasSpamKeyword = spamKeywords.any { keyword ->
                title.contains(keyword) || artist.contains(keyword) || album.contains(keyword)
            }
            if (hasSpamKeyword) return@filter false

            // 2. DURATION FILTER (Between 120s and 330s)
            val durationSeconds = song.durationMs / 1000
            if (durationSeconds < 120 || durationSeconds > 330) return@filter false

            // 3. OFFICIAL LABEL/ALBUM CHECK
            if (album.isBlank()) return@filter false
            val isSpamAlbum = spamAlbums.any { album.contains(it) }
            if (isSpamAlbum) return@filter false

            true
        }
    }
}

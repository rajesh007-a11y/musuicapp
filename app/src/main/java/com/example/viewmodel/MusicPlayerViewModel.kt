package com.example.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.SoundifyAudioPlayer
import com.example.audio.TtsDjSpeaker
import com.example.data.local.CatalogData
import com.example.data.local.PlaylistEntity
import com.example.data.local.SoundifyDatabase
import com.example.data.model.AudioQuality
import com.example.data.model.DjVibe
import com.example.data.model.EqualizerPreset
import com.example.data.model.Song
import com.example.data.model.TasteProfile
import com.example.data.model.UserSettings
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.data.repository.MusicRepository
import com.example.data.repository.SongSearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NavigationTab(val title: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Your Library"),
    AI_DJ("AI DJ")
}

enum class RepeatMode {
    OFF, ALL, ONE
}

class MusicPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val database = SoundifyDatabase.getDatabase(application, viewModelScope)
    private val repository = MusicRepository(
        songDao = database.songDao(),
        listeningDao = database.listeningDao(),
        playlistDao = database.playlistDao()
    )

    private val audioPlayer = SoundifyAudioPlayer(application)
    private val djSpeaker = TtsDjSpeaker(application)

    private val _fetchedHomeSongs = MutableStateFlow<List<Song>>(emptyList())

    // Repository Flows — NO fallback to dummy CatalogData; show only real/fetched songs
    val allSongs: StateFlow<List<Song>> = combine(repository.allSongs, _fetchedHomeSongs) { db, fetched ->
        (fetched + db).distinctBy { it.id }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteSongs: StateFlow<List<Song>> = repository.favoriteSongs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val downloadedSongs: StateFlow<List<Song>> = repository.downloadedSongs.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val recentlyPlayed: StateFlow<List<Song>> = repository.recentlyPlayed.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val heavyRotation: StateFlow<List<Song>> = repository.heavyRotation.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val allPlaylists: StateFlow<List<PlaylistEntity>> = repository.allPlaylists.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), CatalogData.initialPlaylists
    )

    // Player State
    val isPlaying: StateFlow<Boolean> = audioPlayer.isPlaying
    val currentPositionMs: StateFlow<Long> = audioPlayer.currentPositionMs
    val durationMs: StateFlow<Long> = audioPlayer.durationMs
    val visualizerAmplitudes: StateFlow<List<Float>> = audioPlayer.visualizerAmplitudes

    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _currentQueue = MutableStateFlow<List<Song>>(emptyList())
    val currentQueue: StateFlow<List<Song>> = _currentQueue.asStateFlow()

    private val _isShuffle = MutableStateFlow(false)
    val isShuffle: StateFlow<Boolean> = _isShuffle.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    // Audio Quality & Equalizer
    private val _audioQuality = MutableStateFlow(AudioQuality.LOSSLESS_HIFI)
    val audioQuality: StateFlow<AudioQuality> = _audioQuality.asStateFlow()

    private val _currentEqualizer = MutableStateFlow(EqualizerPreset.ELECTRONIC)
    val currentEqualizer: StateFlow<EqualizerPreset> = _currentEqualizer.asStateFlow()

    private val _bassBoostLevel = MutableStateFlow(50) // 0 to 100
    val bassBoostLevel: StateFlow<Int> = _bassBoostLevel.asStateFlow()

    // Offline Mode
    private val _isOfflineOnlyMode = MutableStateFlow(false)
    val isOfflineOnlyMode: StateFlow<Boolean> = _isOfflineOnlyMode.asStateFlow()

    private val _downloadingSongIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingSongIds: StateFlow<Set<String>> = _downloadingSongIds.asStateFlow()

    // User Settings & Language Preferences
    private val _userSettings = MutableStateFlow(UserSettings())
    val userSettings: StateFlow<UserSettings> = _userSettings.asStateFlow()

    private val _isSettingsOpen = MutableStateFlow(false)
    val isSettingsOpen: StateFlow<Boolean> = _isSettingsOpen.asStateFlow()

    // Discovery songs & playlists prioritized by language
    val prioritizedSongs: StateFlow<List<Song>> = combine(allSongs, userSettings) { songs, settings ->
        if (settings.prioritizeInDiscovery) {
            songs.sortedByDescending { song ->
                if (song.language in settings.selectedLanguages) 2 else 0
            }
        } else {
            songs
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val prioritizedPlaylists: StateFlow<List<PlaylistEntity>> = combine(allPlaylists, userSettings) { playlists, settings ->
        if (settings.prioritizeInDiscovery) {
            playlists.sortedByDescending { pl ->
                val matchesHindi = "Hindi" in settings.selectedLanguages && pl.title.contains("Hindi", ignoreCase = true)
                val matchesBengali = "Bengali" in settings.selectedLanguages && (pl.title.contains("Bangla", ignoreCase = true) || pl.title.contains("বাংলা"))
                if (matchesHindi || matchesBengali) 2 else 0
            }
        } else {
            playlists
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), CatalogData.initialPlaylists)

    // Navigation & UI Sheets
    private val _currentTab = MutableStateFlow(NavigationTab.HOME)
    val currentTab: StateFlow<NavigationTab> = _currentTab.asStateFlow()

    private val _isNowPlayingExpanded = MutableStateFlow(false)
    val isNowPlayingExpanded: StateFlow<Boolean> = _isNowPlayingExpanded.asStateFlow()

    private val _selectedPlaylist = MutableStateFlow<PlaylistEntity?>(null)
    val selectedPlaylist: StateFlow<PlaylistEntity?> = _selectedPlaylist.asStateFlow()

    private val _showAudioQualityDialog = MutableStateFlow(false)
    val showAudioQualityDialog: StateFlow<Boolean> = _showAudioQualityDialog.asStateFlow()

    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    val showCreatePlaylistDialog: StateFlow<Boolean> = _showCreatePlaylistDialog.asStateFlow()

    private val _showTrainDjDialog = MutableStateFlow(false)
    val showTrainDjDialog: StateFlow<Boolean> = _showTrainDjDialog.asStateFlow()

    // JioSaavn Global Search Integration
    private val songSearchRepository = SongSearchRepository()
    private var saavnSearchJob: Job? = null
    private val _saavnSearchResults = MutableStateFlow<List<Song>>(emptyList())
    val saavnSearchResults: StateFlow<List<Song>> = _saavnSearchResults.asStateFlow()

    // Search
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchLoading = MutableStateFlow(false)
    val isSearchLoading: StateFlow<Boolean> = _isSearchLoading.asStateFlow()

    val searchResults: StateFlow<List<Song>> = combine(allSongs, _saavnSearchResults, searchQuery) { localSongs, saavnSongs, query ->
        if (query.isBlank()) {
            emptyList()
        } else {
            val local = localSongs.filter { song ->
                song.title.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.genre.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true)
            }
            (local + saavnSongs).distinctBy { it.id }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // AI DJ State
    private val _tasteProfile = MutableStateFlow(TasteProfile())
    val tasteProfile: StateFlow<TasteProfile> = _tasteProfile.asStateFlow()

    private val _currentDjVibe = MutableStateFlow(DjVibe.BALANCED)
    val currentDjVibe: StateFlow<DjVibe> = _currentDjVibe.asStateFlow()

    private val _isDjThinking = MutableStateFlow(false)
    val isDjThinking: StateFlow<Boolean> = _isDjThinking.asStateFlow()

    val isDjSpeaking: StateFlow<Boolean> = djSpeaker.isSpeaking

    private val _isTtsVoiceEnabled = MutableStateFlow(true)
    val isTtsVoiceEnabled: StateFlow<Boolean> = _isTtsVoiceEnabled.asStateFlow()

    private val _isTrainingAi = MutableStateFlow(false)
    val isTrainingAi: StateFlow<Boolean> = _isTrainingAi.asStateFlow()

    private val _trainingProgressStep = MutableStateFlow("")
    val trainingProgressStep: StateFlow<String> = _trainingProgressStep.asStateFlow()

    private var songStartTimeMs = 0L

    init {
        audioPlayer.setOnCompletionListener {
            handleSongCompleted()
        }

        // When a song can't be played (no network, invalid file), skip to next
        audioPlayer.setOnErrorListener { failedSong ->
            android.util.Log.w("MusicPlayerVM", "Song failed to play: ${failedSong.title}, skipping...")
            viewModelScope.launch {
                delay(300) // Small delay to avoid rapid-fire skips
                nextTrack()
            }
        }

        // Initialize taste profile
        refreshTasteProfile()
        
        // Fetch real trending/top songs for the Home Screen instead of dummy data
        fetchTrendingHomeSongs()
    }

    private fun fetchTrendingHomeSongs() {
        viewModelScope.launch {
            try {
                // Fetch top trending Hindi/Bollywood hits
                val results1 = songSearchRepository.searchDomainSongs("Top Bollywood")
                val results2 = songSearchRepository.searchDomainSongs("Trending English")
                val combined = (results1 + results2).distinctBy { it.id }.shuffled()
                if (combined.isNotEmpty()) {
                    _fetchedHomeSongs.value = combined
                    if (_currentQueue.value.isEmpty() || _currentQueue.value == CatalogData.initialSongs) {
                        _currentQueue.value = combined
                        _currentSong.value = combined.first()
                    }
                }
            } catch (e: Exception) {
                // Keep dummy data if fetch fails
            }
        }
    }

    fun setTab(tab: NavigationTab) {
        _currentTab.value = tab
    }

    fun setNowPlayingExpanded(expanded: Boolean) {
        _isNowPlayingExpanded.value = expanded
    }

    fun selectPlaylist(playlist: PlaylistEntity?) {
        _selectedPlaylist.value = playlist
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        saavnSearchJob?.cancel()
        if (query.isNotBlank() && query.trim().length >= 2) {
            _isSearchLoading.value = true
            saavnSearchJob = viewModelScope.launch {
                delay(150) // Fast debounce for snappy results
                try {
                    val remoteSongs = songSearchRepository.searchDomainSongs(query.trim())
                    _saavnSearchResults.value = remoteSongs
                } catch (_: Exception) {
                    // Network offline — keep whatever local results we have
                } finally {
                    _isSearchLoading.value = false
                }
            }
        } else {
            _saavnSearchResults.value = emptyList()
            _isSearchLoading.value = false
        }
    }

    fun setAudioQuality(quality: AudioQuality) {
        _audioQuality.value = quality
    }

    fun setEqualizerPreset(preset: EqualizerPreset) {
        _currentEqualizer.value = preset
    }

    fun setBassBoost(level: Int) {
        _bassBoostLevel.value = level.coerceIn(0, 100)
    }

    fun setOfflineMode(enabled: Boolean) {
        _isOfflineOnlyMode.value = enabled
    }

    fun toggleOfflineMode() {
        _isOfflineOnlyMode.value = !_isOfflineOnlyMode.value
    }

    fun setShowAudioQualityDialog(show: Boolean) {
        _showAudioQualityDialog.value = show
    }

    fun setShowCreatePlaylistDialog(show: Boolean) {
        _showCreatePlaylistDialog.value = show
    }

    fun setShowTrainDjDialog(show: Boolean) {
        _showTrainDjDialog.value = show
    }

    fun toggleTtsVoice() {
        _isTtsVoiceEnabled.value = !_isTtsVoiceEnabled.value
        if (!_isTtsVoiceEnabled.value) {
            djSpeaker.stop()
        }
    }

    /** Check if device currently has internet connectivity */
    private fun isNetworkAvailable(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun playSong(song: Song, queue: List<Song>? = null) {
        if (_isOfflineOnlyMode.value && !song.isDownloaded) {
            return
        }

        // Record listening stats for previous song
        _currentSong.value?.let { prev ->
            val listenedMs = System.currentTimeMillis() - songStartTimeMs
            viewModelScope.launch {
                repository.recordListeningEvent(
                    song = prev,
                    durationListenedMs = listenedMs,
                    wasCompleted = false,
                    wasSkipped = true
                )
            }
        }

        _currentSong.value = song
        songStartTimeMs = System.currentTimeMillis()

        if (queue != null) {
            _currentQueue.value = queue
        }

        audioPlayer.playSong(song)
    }

    fun togglePlayPause() {
        if (_currentSong.value == null) {
            _currentQueue.value.firstOrNull()?.let { playSong(it) }
        } else {
            audioPlayer.togglePlayPause()
        }
    }

    fun seekTo(positionMs: Long) {
        audioPlayer.seekTo(positionMs)
    }

    fun nextTrack() {
        val queue = _currentQueue.value
        if (queue.isEmpty()) return

        val current = _currentSong.value
        val currentIndex = queue.indexOfFirst { it.id == current?.id }

        if (currentIndex != -1 && currentIndex + 1 >= queue.size && !_isOfflineOnlyMode.value) {
            // We reached the end of the queue, let's fetch an AI-recommended next song automatically!
            _isDjThinking.value = true
            viewModelScope.launch {
                try {
                    var newSongsToAdd: List<Song> = emptyList()

                    if (current != null) {
                        val similarSongs = songSearchRepository.getSimilarDomainSongs(current.id)
                        
                        // 1 & 2. Strict Filtering: Garbage Blacklist & Duplicate Prevention
                        val blacklist = listOf("dj", "remix", "mix", "lofi", "slowed", "reverb", "8d", "mashup", "lo-fi", "instrumental")
                        
                        fun cleanTitle(t: String) = t.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").trim().lowercase()
                        val queueTitles = queue.map { cleanTitle(it.title) }.toSet()
                        
                        val sanitized = similarSongs.filter { song ->
                            val lowerTitle = song.title.lowercase()
                            val lowerArtist = song.artist.lowercase()
                            !blacklist.any { lowerTitle.contains(it) || lowerArtist.contains(it) }
                        }
                        
                        val uniqueSongs = mutableListOf<Song>()
                        val seenTitles = queueTitles.toMutableSet()
                        
                        for (song in sanitized) {
                            val cTitle = cleanTitle(song.title)
                            if (cTitle.isNotEmpty() && !seenTitles.contains(cTitle)) {
                                seenTitles.add(cTitle)
                                uniqueSongs.add(song)
                            }
                        }
                        
                        // 3. Primary Artist Prioritization
                        val currentArtists = current.artist.lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        newSongsToAdd = uniqueSongs.sortedByDescending { song ->
                            val songArtist = song.artist.lowercase()
                            if (currentArtists.any { songArtist.contains(it) }) 1 else 0
                        }
                    }

                    if (newSongsToAdd.isEmpty()) {
                        // Fallback to old search method
                        val fallbackQuery = current?.let { "${it.artist} ${it.genre} hits" } ?: "Top Bollywood hits"
                        val topGenre = _tasteProfile.value.topGenres.firstOrNull()?.genre ?: "Pop"
                        val nextQuery = if (current != null) {
                            listOf(
                                "${current.artist} best songs",
                                "${current.genre} ${topGenre} hits",
                                "${current.artist} new",
                                "Similar to ${current.title} by ${current.artist}"
                            ).random()
                        } else {
                            fallbackQuery
                        }

                        val results = songSearchRepository.searchDomainSongs(nextQuery)
                        newSongsToAdd = results.filter { song -> !queue.any { it.id == song.id } }
                    }

                    if (newSongsToAdd.isNotEmpty()) {
                        val newQueue = queue + newSongsToAdd
                        _currentQueue.value = newQueue
                        playSong(newSongsToAdd.first())
                        _isDjThinking.value = false
                        return@launch
                    }
                } catch (e: Exception) {
                    // Ignored, fallback to normal logic
                }
                
                _isDjThinking.value = false
                // Fallback if AI fetch fails
                val fallbackIndex = if (_isShuffle.value) queue.indices.random() else 0
                playSong(queue.getOrNull(fallbackIndex) ?: queue.first())
            }
            return
        }

        val nextIndex = if (_isShuffle.value) {
            queue.indices.random()
        } else {
            if (currentIndex + 1 < queue.size) currentIndex + 1 else 0
        }

        val nextSong = queue.getOrNull(nextIndex) ?: queue.first()
        playSong(nextSong)
    }

    fun previousTrack() {
        // If current position is greater than 3 seconds, restart current track
        if (currentPositionMs.value > 3000L) {
            audioPlayer.seekTo(0L)
            return
        }

        val queue = _currentQueue.value
        if (queue.isEmpty()) return

        val current = _currentSong.value
        val currentIndex = queue.indexOfFirst { it.id == current?.id }

        val prevIndex = if (currentIndex - 1 >= 0) currentIndex - 1 else queue.lastIndex
        val prevSong = queue.getOrNull(prevIndex) ?: queue.last()
        playSong(prevSong)
    }

    fun toggleShuffle() {
        _isShuffle.value = !_isShuffle.value
    }

    fun toggleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    private fun handleSongCompleted() {
        _currentSong.value?.let { song ->
            viewModelScope.launch {
                repository.recordListeningEvent(
                    song = song,
                    durationListenedMs = song.durationMs,
                    wasCompleted = true,
                    wasSkipped = false
                )
            }
        }

        when (_repeatMode.value) {
            RepeatMode.ONE -> {
                _currentSong.value?.let { playSong(it) }
            }
            RepeatMode.ALL, RepeatMode.OFF -> {
                nextTrack()
            }
        }
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            repository.toggleFavorite(song)
            // Update active song favorite status
            if (_currentSong.value?.id == song.id) {
                _currentSong.value = _currentSong.value?.copy(isFavorite = !song.isFavorite)
            }
        }
    }

    fun downloadSong(song: Song) {
        viewModelScope.launch {
            _downloadingSongIds.value = _downloadingSongIds.value + song.id
            val success = repository.downloadSong(song, getApplication())
            _downloadingSongIds.value = _downloadingSongIds.value - song.id
            if (success && _currentSong.value?.id == song.id) {
                _currentSong.value = _currentSong.value?.copy(isDownloaded = true)
            }
        }
    }

    fun removeDownload(song: Song) {
        viewModelScope.launch {
            repository.removeDownload(song)
            if (_currentSong.value?.id == song.id) {
                _currentSong.value = _currentSong.value?.copy(isDownloaded = false, localFilePath = null)
            }
        }
    }

    fun createPlaylist(name: String, description: String, songIds: List<String> = emptyList()) {
        viewModelScope.launch {
            val ids = if (songIds.isEmpty()) {
                allSongs.value.take(4).map { it.id }
            } else {
                songIds
            }
            repository.createPlaylist(name, description, ids)
            _showCreatePlaylistDialog.value = false
        }
    }

    // User Settings Actions
    fun setSettingsOpen(isOpen: Boolean) {
        _isSettingsOpen.value = isOpen
    }

    fun toggleMusicLanguage(code: String) {
        val current = _userSettings.value.selectedLanguages.toMutableSet()
        if (code in current) {
            if (current.size > 1) { // keep at least 1 language active
                current.remove(code)
            }
        } else {
            current.add(code)
        }
        _userSettings.value = _userSettings.value.copy(selectedLanguages = current)
        refreshTasteProfile()
    }

    fun togglePrioritizeDiscovery() {
        _userSettings.value = _userSettings.value.copy(
            prioritizeInDiscovery = !_userSettings.value.prioritizeInDiscovery
        )
    }

    fun togglePrioritizeDj() {
        _userSettings.value = _userSettings.value.copy(
            prioritizeInDj = !_userSettings.value.prioritizeInDj
        )
        refreshTasteProfile()
    }

    fun setDjVoicePersona(persona: String) {
        _userSettings.value = _userSettings.value.copy(djVoicePersona = persona)
        refreshTasteProfile()
    }

    fun resetTasteProfile() {
        refreshTasteProfile(_currentDjVibe.value)
    }

    // AI DJ Controls
    fun setDjVibe(vibe: DjVibe) {
        _currentDjVibe.value = vibe
        refreshTasteProfile(vibe)
    }

    fun refreshTasteProfile(vibe: DjVibe = _currentDjVibe.value) {
        viewModelScope.launch {
            _isDjThinking.value = true
            val profile = repository.computeTasteProfile(
                vibe = vibe,
                selectedLanguages = _userSettings.value.selectedLanguages
            )
            _tasteProfile.value = profile
            _isDjThinking.value = false

            // If voice enabled, speak the commentary
            if (_isTtsVoiceEnabled.value && profile.djIntroCommentary.isNotBlank()) {
                djSpeaker.speak(profile.djIntroCommentary)
            }
        }
    }

    fun startDjSession(vibe: DjVibe = _currentDjVibe.value) {
        viewModelScope.launch {
            _isDjThinking.value = true
            _currentDjVibe.value = vibe
            val profile = repository.computeTasteProfile(
                vibe = vibe,
                selectedLanguages = _userSettings.value.selectedLanguages
            )
            _tasteProfile.value = profile
            val curatedSongs = repository.getCuratedSongsForVibe(
                vibe = vibe,
                selectedLanguages = _userSettings.value.selectedLanguages,
                prioritizeLanguages = _userSettings.value.prioritizeInDj
            )
            _currentQueue.value = curatedSongs
            _isDjThinking.value = false

            // Voice intro
            if (_isTtsVoiceEnabled.value) {
                djSpeaker.speak(profile.djIntroCommentary)
            }

            curatedSongs.firstOrNull()?.let { playSong(it, curatedSongs) }
        }
    }

    fun speakCurrentDjCommentary() {
        val commentary = _tasteProfile.value.djIntroCommentary
        if (commentary.isNotBlank()) {
            djSpeaker.speak(commentary)
        }
    }

    fun stopDjSpeaking() {
        djSpeaker.stop()
    }

    fun triggerTrainAiModel() {
        viewModelScope.launch {
            _isTrainingAi.value = true
            _trainingProgressStep.value = "Analyzing listening session logs & skip curves..."
            delay(600)
            _trainingProgressStep.value = "Extracting energy spectrum & valence coordinates..."
            delay(700)
            _trainingProgressStep.value = "Fine-tuning neural taste vectors with Gemini..."
            delay(800)
            _trainingProgressStep.value = "Calibrating personalized DJ voice & curation persona..."
            delay(500)

            val updatedProfile = repository.computeTasteProfile(_currentDjVibe.value)
            _tasteProfile.value = updatedProfile
            _isTrainingAi.value = false

            if (_isTtsVoiceEnabled.value) {
                djSpeaker.speak("AI training complete! I've locked in your exact taste profile.")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
        djSpeaker.release()
    }
}

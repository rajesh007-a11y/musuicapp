package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.MusicRepository
import com.example.data.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class UiState {
    object Idle : UiState()
    object Loading : UiState()
    data class Success(val songs: List<Song>) : UiState()
    data class Error(val message: String) : UiState()
}

class MusicViewModel : ViewModel() {
    private val repository = MusicRepository()
    
    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()
    
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            repository.searchSongs(query)
                .onSuccess { songs ->
                    _uiState.value = UiState.Success(songs)
                }
                .onFailure { error ->
                    _uiState.value = UiState.Error(error.message ?: "Unknown Error")
                }
        }
    }

    fun playSong(song: Song) {
        _currentSong.value = song
    }
    
    fun setPlayingState(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }
}

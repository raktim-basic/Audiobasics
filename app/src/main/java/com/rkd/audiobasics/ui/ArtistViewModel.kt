package com.rkd.audiobasics.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rkd.audiobasics.api.Innertube
import com.rkd.audiobasics.data.ArtistPage
import kotlinx.coroutines.launch

class ArtistViewModel : ViewModel() {

    var artistPage by mutableStateOf<ArtistPage?>(null)
        private set
    var isLoading by mutableStateOf(true)
        private set
    var hasError by mutableStateOf(false)
        private set

    var selectedTab by mutableIntStateOf(0)

    private var loadedForKey: String? = null

    fun loadIfNeeded(artistName: String, artistBrowseId: String) {
        val key = "$artistName|$artistBrowseId"
        if (loadedForKey == key) return
        loadedForKey = key
        isLoading = true
        hasError = false
        viewModelScope.launch {
            try {
                val page = if (artistBrowseId.isNotBlank()) {
                    Innertube.getArtistPage(artistBrowseId)
                } else {
                    Innertube.searchArtistByName(artistName)
                }
                artistPage = page
                hasError = page == null
            } catch (_: Exception) {
                hasError = true
            } finally {
                isLoading = false
            }
        }
    }
}

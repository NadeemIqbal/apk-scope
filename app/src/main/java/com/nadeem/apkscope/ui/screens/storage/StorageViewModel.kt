package com.nadeem.apkscope.ui.screens.storage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nadeem.apkscope.domain.storage.StorageCategory
import com.nadeem.apkscope.domain.storage.StorageRepository
import com.nadeem.apkscope.domain.storage.StorageSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StorageUiState(
    val summary: StorageSummary? = null,
    val isLoading: Boolean = true,
    val deleting: StorageCategory? = null,
    val error: String? = null,
)

class StorageViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = StorageRepository(application)
    private val _uiState = MutableStateFlow(StorageUiState())
    val uiState: StateFlow<StorageUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.deleting != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            runCatching { repository.snapshot() }
                .onSuccess { summary -> _uiState.value = StorageUiState(summary = summary, isLoading = false) }
                .onFailure { error ->
                    _uiState.value = StorageUiState(
                        isLoading = false,
                        error = error.message ?: "Storage could not be read.",
                    )
                }
        }
    }

    fun delete(category: StorageCategory) {
        if (_uiState.value.deleting != null) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(deleting = category, error = null)
            runCatching { repository.delete(category) }
                .onSuccess { summary ->
                    val refreshed = runCatching { repository.snapshot() }.getOrNull()
                    _uiState.value = StorageUiState(summary = refreshed, isLoading = false)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        deleting = null,
                        isLoading = false,
                        error = error.message ?: "Nothing was deleted.",
                    )
                }
        }
    }
}

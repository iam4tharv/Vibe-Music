package com.music.echo.ui.screens.equalizer.autoeq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.echo.eq.autoeq.AutoEqApi
import com.music.echo.eq.autoeq.AutoEqEntry
import com.music.echo.eq.data.EQProfileRepository
import com.music.echo.eq.data.ParametricEQParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AutoEqState(
    val isLoading: Boolean = false,
    val isImporting: Boolean = false,
    val entries: List<AutoEqEntry> = emptyList(),
    val filteredEntries: List<AutoEqEntry> = emptyList(),
    val searchQuery: String = "",
    val error: String? = null,
    val importSuccessMessage: String? = null
)

@HiltViewModel
class AutoEqViewModel @Inject constructor(
    private val api: AutoEqApi,
    private val repository: EQProfileRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AutoEqState())
    val state: StateFlow<AutoEqState> = _state.asStateFlow()

    init {
        loadIndex()
    }

    private fun loadIndex() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, error = null) }
            val entries = api.getIndex()
            if (entries.isEmpty()) {
                _state.update { it.copy(isLoading = false, error = "Failed to load AutoEq database.") }
            } else {
                _state.update { it.copy(isLoading = false, entries = entries, filteredEntries = entries) }
            }
        }
    }

    fun search(query: String) {
        val filtered = if (query.isBlank()) {
            _state.value.entries
        } else {
            _state.value.entries.filter { 
                it.name.contains(query, ignoreCase = true) || 
                it.source.contains(query, ignoreCase = true) 
            }
        }
        _state.update { it.copy(searchQuery = query, filteredEntries = filtered) }
    }

    fun importProfile(entry: AutoEqEntry) {
        viewModelScope.launch {
            _state.update { it.copy(isImporting = true, error = null, importSuccessMessage = null) }
            
            val eqText = api.getParametricEq(entry)
            if (eqText == null) {
                _state.update { it.copy(isImporting = false, error = "Failed to download EQ data.") }
                return@launch
            }

            try {
                val parametricEQ = ParametricEQParser.parseText(eqText)
                val validationErrors = ParametricEQParser.validate(parametricEQ)
                
                if (validationErrors.isNotEmpty()) {
                    _state.update { it.copy(isImporting = false, error = "Invalid EQ data: ${validationErrors.first()}") }
                    return@launch
                }
                
                repository.importCustomProfile(entry.name, parametricEQ)
                _state.update { 
                    it.copy(
                        isImporting = false, 
                        importSuccessMessage = "Successfully imported ${entry.name}"
                    ) 
                }
            } catch (e: Exception) {
                _state.update { it.copy(isImporting = false, error = e.message ?: "Failed to import.") }
            }
        }
    }

    fun clearMessages() {
        _state.update { it.copy(error = null, importSuccessMessage = null) }
    }
}

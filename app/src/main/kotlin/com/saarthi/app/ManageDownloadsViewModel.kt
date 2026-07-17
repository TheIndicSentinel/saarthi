package com.saarthi.app

import android.app.Application
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saarthi.core.inference.DebugLogger
import com.saarthi.core.inference.ModelCatalog
import com.saarthi.core.inference.ModelDownloadManager
import com.saarthi.core.inference.engine.InferenceEngine
import com.saarthi.core.inference.model.ModelEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DownloadedModel(
    val entry: ModelEntry,
    val sizeBytes: Long,
    val active: Boolean,
)

data class ManageDownloadsUiState(
    val installed: List<DownloadedModel> = emptyList(),
    val phoneTotalBytes: Long = 0,
    val phoneFreeBytes: Long = 0,
    val activeModelName: String? = null,
    val error: String? = null,
)

@HiltViewModel
class ManageDownloadsViewModel @Inject constructor(
    application: Application,
    private val modelCatalog: ModelCatalog,
    private val downloadManager: ModelDownloadManager,
    private val inferenceEngine: InferenceEngine,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ManageDownloadsUiState())
    val uiState: StateFlow<ManageDownloadsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val active = inferenceEngine.activeModelName
            val installed = modelCatalog.allModels
                .filter { downloadManager.isDownloaded(it) }
                .map { m ->
                    val file = downloadManager.localPathFor(m)
                    DownloadedModel(
                        entry = m,
                        sizeBytes = if (file.exists()) file.length() else 0,
                        active = active == m.displayName,
                    )
                }
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        installed = installed,
                        phoneTotalBytes = total,
                        phoneFreeBytes = free,
                        activeModelName = active,
                        error = null,
                    )
                }
            }
        }
    }

    fun deleteModel(model: ModelEntry) {
        // Backstop, not the primary control — SettingsScreen already disables
        // the delete affordance for the active model (DownloadedModel.active,
        // computed in refresh() above). This guard exists because UI-level
        // disabling alone isn't a reliable data-integrity control (stale
        // recomposition, a race between engine state and the button's
        // enabled flag) — deleting a file the engine currently has mmap'd
        // open is a real correctness risk, not just a UI nicety.
        if (inferenceEngine.activeModelName == model.displayName) {
            DebugLogger.log("DELETE", "Blocked: ${model.id} is the active model")
            _uiState.update { it.copy(error = "Can't delete the model that's currently active — switch to a different model first.") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val file = downloadManager.localPathFor(model)
            DebugLogger.log("DELETE", "From manage screen: ${file.absolutePath}")
            downloadManager.cancelDownload(model)
            file.delete()
            refresh()
        }
    }
}

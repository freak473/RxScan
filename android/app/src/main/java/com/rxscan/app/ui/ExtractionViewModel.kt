package com.rxscan.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rxscan.app.data.ExtractionRepository
import com.rxscan.app.data.Medication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException

/** Why an extraction failed — drives honest, cause-specific copy on the Extracting screen. */
enum class ExtractError { VISION_UNAVAILABLE, NETWORK, BAD_IMAGE, EMPTY, UNKNOWN }

sealed interface ExtractionState {
    data object Loading : ExtractionState
    data class Success(val meds: List<Medication>) : ExtractionState
    data class Error(val cause: ExtractError) : ExtractionState
}

/**
 * Owns the POST /extract call and its Loading/Success/Error lifecycle so it
 * survives config changes and cancels cleanly. Reads the image URI through the
 * application context in the repository.
 */
class ExtractionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ExtractionRepository(app.applicationContext)

    private val _state = MutableStateFlow<ExtractionState>(ExtractionState.Loading)
    val state: StateFlow<ExtractionState> = _state.asStateFlow()

    fun run(uri: Uri) {
        _state.value = ExtractionState.Loading
        viewModelScope.launch {
            _state.value = try {
                val meds = repo.extract(uri)
                if (meds.isEmpty()) ExtractionState.Error(ExtractError.EMPTY)
                else ExtractionState.Success(meds)
            } catch (e: HttpException) {
                ExtractionState.Error(
                    when (e.code()) {
                        503 -> ExtractError.VISION_UNAVAILABLE
                        400, 413, 415 -> ExtractError.BAD_IMAGE
                        else -> ExtractError.UNKNOWN
                    },
                )
            } catch (_: IOException) {
                ExtractionState.Error(ExtractError.NETWORK)
            } catch (_: Exception) {
                ExtractionState.Error(ExtractError.UNKNOWN)
            }
        }
    }
}

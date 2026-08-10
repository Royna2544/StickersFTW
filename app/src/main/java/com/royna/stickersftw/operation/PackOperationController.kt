package com.royna.stickersftw.operation

import com.royna.stickersftw.model.ConversionUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-scoped state for the one pack operation that may run at a time.
 *
 * This used to live in the ViewModel, which tied both the state and the work
 * to whichever Activity happened to be alive. Holding it here instead means
 * the Conversion screen can be destroyed and rebuilt -- or never opened at
 * all -- without the operation noticing, and a notification tap that reopens
 * the app lands on live progress rather than a blank screen.
 *
 * Only [PackOperationService] writes; everything else observes. */
object PackOperationController {
    private val _state = MutableStateFlow(ConversionUiState())
    val state: StateFlow<ConversionUiState> = _state.asStateFlow()

    /** Telegram and the companion server both flood-limit, so one at a time.
     * Asking for the pack that is already running is not a rejection -- it's
     * how reopening the Conversion screen re-attaches. */
    @Synchronized
    fun canStart(packId: String): Boolean {
        val current = _state.value
        return !current.isRunning || current.packId == packId
    }

    @Synchronized
    fun isRunningFor(packId: String): Boolean =
        _state.value.isRunning && _state.value.packId == packId

    val isRunning: Boolean get() = _state.value.isRunning

    fun publish(state: ConversionUiState) {
        _state.value = state
    }
}

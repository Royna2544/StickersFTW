package com.royna.stickersftw.operation

import com.royna.stickersftw.model.ConversionUiState
import kotlinx.coroutines.CompletableDeferred
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

    // ---- Mixed-pack question ---------------------------------------------

    private val _question = MutableStateFlow<MixedPackQuestion?>(null)

    /** Non-null while a conversion is blocked waiting for an answer. Lives
     * here rather than in the ViewModel for the same reason the progress
     * does: the work asking the question outlives any Activity, and the
     * dialog has to be able to appear on whatever screen is open. */
    val question: StateFlow<MixedPackQuestion?> = _question.asStateFlow()

    private var pendingAnswer: CompletableDeferred<Boolean>? = null

    suspend fun askMixedPack(animatedCount: Int, staticCount: Int): Boolean {
        val answer = CompletableDeferred<Boolean>()
        pendingAnswer = answer
        _question.value = MixedPackQuestion(animatedCount, staticCount)
        return try {
            answer.await()
        } finally {
            _question.value = null
            pendingAnswer = null
        }
    }

    fun answerMixedPack(splitByType: Boolean) {
        pendingAnswer?.complete(splitByType)
    }
}

/** A conversion found both animated and static stickers in one pack, which
 * WhatsApp will not accept, and is waiting to be told what to do about it. */
data class MixedPackQuestion(val animatedCount: Int, val staticCount: Int)

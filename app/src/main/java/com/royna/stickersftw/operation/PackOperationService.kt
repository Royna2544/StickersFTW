package com.royna.stickersftw.operation

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.royna.stickersftw.R
import com.royna.stickersftw.data.SettingsRepository
import com.royna.stickersftw.data.StickerPackRepository
import com.royna.stickersftw.data.model.PackOperationProgress
import com.royna.stickersftw.model.ConversionUiState
import com.royna.stickersftw.model.backendConfig
import com.royna.stickersftw.notifications.PackOperationNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Runs one pack operation as a foreground service.
 *
 * The work used to live in the ViewModel's scope, which meant it was only
 * ever as durable as the Activity: leaving the app put the process on the
 * shortlist for reclaim, and a conversion that takes six minutes is a long
 * time to ask someone to stare at a progress bar. A foreground service is
 * the only thing that actually tells Android this work matters.
 *
 * The notification is therefore no longer optional -- a foreground service
 * cannot run without one -- so it is posted the moment the operation starts
 * rather than when the user opts in. "Run in background" is now just a way
 * out of the screen; the work was never tied to it.
 *
 * Typed dataSync rather than shortService: shortService caps out at three
 * minutes and cannot be extended, and these routinely run past that. */
class PackOperationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val request = PackOperationRequest.readFrom(intent)
        if (request == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        PackOperationNotifier.ensureChannel(this)
        // Must happen within a few seconds of the start call or Android kills
        // the process, so it uses whatever is already known rather than
        // waiting for the first real progress.
        startForegroundCompat(
            request,
            getString(R.string.stage_starting),
            0f,
        )

        if (!PackOperationController.canStart(request.packId)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (PackOperationController.isRunningFor(request.packId)) {
            // Already running this exact pack -- the caller is re-attaching.
            return START_NOT_STICKY
        }

        val startedAt = System.currentTimeMillis()
        PackOperationController.publish(
            ConversionUiState(
                packId = request.packId,
                stage = getString(R.string.stage_starting),
                isRunning = true,
                startedAtMillis = startedAt,
            ),
        )

        job?.cancel()
        job = scope.launch {
            var slowFormat = false
            try {
                buildFlow(request).collect { progress ->
                    if (progress is PackOperationProgress.Progress && progress.slowFormat) slowFormat = true
                    PackOperationController.publish(progress.toUiState(request.packId, startedAt, slowFormat))
                    notify(request, progress)
                }
            } finally {
                // DETACH so the terminal success/failure notification survives
                // the service going away.
                ServiceCompat.stopForeground(this@PackOperationService, ServiceCompat.STOP_FOREGROUND_DETACH)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun buildFlow(request: PackOperationRequest): Flow<PackOperationProgress> {
        val settings = SettingsRepository(applicationContext).settings.first()
        val packs = StickerPackRepository(applicationContext)
        return when (request) {
            is PackOperationRequest.Import -> packs.importAndConvert(
                request.packId,
                settings.backendConfig,
                request.input,
                request.partIndex,
                settings.conversionBias,
                PackOperationController::askMixedPack,
            )
            is PackOperationRequest.ImportCustom -> packs.importAndConvertCustom(
                request.packId,
                settings.backendConfig,
                request.input,
                request.selectedIds,
                settings.conversionBias,
                PackOperationController::askMixedPack,
            )
            is PackOperationRequest.Update -> packs.applyPackUpdate(
                request.packId,
                settings.backendConfig,
                settings.conversionBias,
                PackOperationController::askMixedPack,
            )
            is PackOperationRequest.Publish -> packs.publishPack(
                request.packId,
                request.pushToTelegram,
                request.addToWhatsapp,
                settings.backendConfig,
                settings.telegramUserId,
                settings.conversionBias,
            )
            is PackOperationRequest.AddStickers -> packs.addStickersToPack(
                request.packId,
                request.items,
                settings.conversionBias,
            )
            is PackOperationRequest.EditSticker -> packs.editSticker(
                request.packId,
                request.rowId,
                request.item,
                settings.conversionBias,
            )
        }
    }

    private fun notify(request: PackOperationRequest, progress: PackOperationProgress) {
        when (progress) {
            is PackOperationProgress.Progress ->
                startForegroundCompat(request, progress.stage, progress.fraction)
            is PackOperationProgress.Complete -> PackOperationNotifier.showSuccess(
                this,
                request.packId,
                request.packTitle,
                getString(R.string.conversion_ready_body),
            )
            is PackOperationProgress.Failed -> PackOperationNotifier.showFailure(
                this,
                request.packId,
                request.packTitle,
                progress.message,
            )
        }
    }

    /** Re-calling startForeground is how a foreground notification is
     * updated; posting it separately would leave the service's own copy
     * frozen at whatever it started with. */
    private fun startForegroundCompat(request: PackOperationRequest, stage: String, fraction: Float) {
        val notification = PackOperationNotifier.buildProgress(this, request.packId, request.packTitle, stage, fraction)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, PackOperationNotifier.idFor(request.packId), notification, type)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        fun start(context: Context, request: PackOperationRequest) {
            val intent = request.writeTo(Intent(context, PackOperationService::class.java))
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

/** Mirrors a pipeline progress event into the shape the UI observes. Lives
 * here rather than in the ViewModel because the service is what produces it
 * now; the ViewModel only reads the result. */
private fun PackOperationProgress.toUiState(
    packId: String,
    startedAtMillis: Long,
    slowFormat: Boolean,
): ConversionUiState = when (this) {
    is PackOperationProgress.Progress -> ConversionUiState(
        packId = packId,
        stage = stage,
        progress = fraction,
        isRunning = true,
        startedAtMillis = startedAtMillis,
        isSlowFormat = slowFormat,
    )
    is PackOperationProgress.Complete -> ConversionUiState(
        packId = packId,
        stage = "Ready",
        progress = 1f,
        isRunning = false,
        isComplete = true,
        startedAtMillis = startedAtMillis,
        isSlowFormat = slowFormat,
        splitPackId = splitPackId,
    )
    is PackOperationProgress.Failed -> ConversionUiState(
        packId = packId,
        stage = "Failed",
        isRunning = false,
        errorMessage = message,
        startedAtMillis = startedAtMillis,
        isSlowFormat = slowFormat,
    )
}

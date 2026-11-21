package com.example.myapplication.mediacatalog

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Extracts audio from a local video selected by the user or downloaded from a source
 * that has already passed LoopBox's rights and direct-file checks. Output is M4A/AAC.
 */
class LoopBoxVideoToAudioConverter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun extractToM4a(inputVideo: Uri, outputM4a: File): File =
        suspendCancellableCoroutine { continuation ->
            outputM4a.parentFile?.mkdirs()
            if (outputM4a.exists() && !outputM4a.delete()) {
                continuation.resumeWithException(IllegalStateException("Cannot replace output file."))
                return@suspendCancellableCoroutine
            }
            val completed = AtomicBoolean(false)
            val transformer = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (completed.compareAndSet(false, true)) {
                            if (outputM4a.isFile && outputM4a.length() > 0L) {
                                continuation.resume(outputM4a)
                            } else {
                                continuation.resumeWithException(IllegalStateException("Converter produced no audio file."))
                            }
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resumeWithException(exportException)
                        }
                    }
                })
                .build()

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) transformer.cancel()
            }
            try {
                val edited = EditedMediaItem.Builder(MediaItem.fromUri(inputVideo))
                    .setRemoveVideo(true)
                    .build()
                transformer.start(edited, outputM4a.absolutePath)
            } catch (error: Throwable) {
                if (completed.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }
}

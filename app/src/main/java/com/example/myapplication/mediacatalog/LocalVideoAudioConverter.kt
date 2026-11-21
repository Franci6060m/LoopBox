package com.example.myapplication.mediacatalog

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
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
 * Extracts the audio track from a user-selected local video into an M4A/AAC file.
 * This deliberately accepts a local content/file URI, not arbitrary streaming-service URLs.
 * The caller is responsible for selecting media they own or are authorised to convert.
 */
class LocalVideoAudioConverter(context: Context) {
    private val appContext = context.applicationContext

    suspend fun extractAudio(input: Uri, output: File): File =
        suspendCancellableCoroutine { continuation ->
            output.parentFile?.mkdirs()
            if (output.exists() && !output.delete()) {
                continuation.resumeWithException(IllegalStateException("Cannot replace output file"))
                return@suspendCancellableCoroutine
            }
            val finished = AtomicBoolean(false)
            val transformer = Transformer.Builder(appContext)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        if (finished.compareAndSet(false, true)) {
                            if (output.isFile && output.length() > 0L) continuation.resume(output)
                            else continuation.resumeWithException(IllegalStateException("Audio export produced no file"))
                        }
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (finished.compareAndSet(false, true)) continuation.resumeWithException(exportException)
                    }
                })
                .build()
            val edited = EditedMediaItem.Builder(MediaItem.fromUri(input))
                .setRemoveVideo(true)
                .build()
            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) transformer.cancel()
            }
            try {
                transformer.start(edited, output.absolutePath)
            } catch (error: Throwable) {
                if (finished.compareAndSet(false, true)) continuation.resumeWithException(error)
            }
        }
}

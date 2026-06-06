package com.aifacestudio.domain

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.aifacestudio.core.MediaRules
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.MediaValidationResult
import com.aifacestudio.data.model.SelectedMedia
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaValidator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    suspend fun validateSource(uri: Uri, mediaType: MediaType): MediaValidationResult =
        withContext(Dispatchers.IO) {
            persistReadPermission(uri)
            val info = readFileInfo(uri)
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()
            val mimeTypes = MediaRules.sourceMimeTypes(mediaType)
            val mimeType = info.mimeType

            if (mimeType == null || mimeTypes.none { matchesMime(mimeType, it) }) {
                errors += "Unsupported ${mediaType.noun.lowercase()} format."
            }
            if (info.sizeBytes > MediaRules.maxBytes(mediaType)) {
                errors += "${mediaType.noun} exceeds the maximum file size."
            }

            val media = when (mediaType) {
                MediaType.IMAGE -> readImageInfo(uri, info)
                MediaType.GIF -> readImageInfo(uri, info)
                MediaType.VIDEO -> readVideoInfo(uri, info)
            }

            if (mediaType != MediaType.VIDEO) {
                val longEdge = maxOf(media.width ?: 0, media.height ?: 0)
                if (longEdge > MediaRules.FOUR_K_LONG_EDGE) {
                    errors += "Resolution is above the supported 4K limit."
                }
            } else if ((media.durationMs ?: 0L) > MediaRules.VIDEO_MAX_DURATION_MS) {
                errors += "Video is longer than the 10 minute limit."
            }

            if (media.width == null || media.height == null) {
                warnings += "Resolution could not be read."
            }
            MediaValidationResult(media = media, errors = errors, warnings = warnings)
        }

    suspend fun validateFace(uri: Uri): MediaValidationResult =
        withContext(Dispatchers.IO) {
            persistReadPermission(uri)
            val info = readFileInfo(uri)
            val media = readImageInfo(uri, info)
            val errors = mutableListOf<String>()
            val warnings = mutableListOf<String>()

            if (info.mimeType == null || !info.mimeType.startsWith("image/")) {
                errors += "Face input must be an image."
            }
            if (info.mimeType != null && info.mimeType !in supportedFaceMimeTypes) {
                errors += "Face image must be JPG, PNG, or WEBP."
            }
            if (info.sizeBytes > MediaRules.IMAGE_MAX_BYTES) {
                errors += "Face image exceeds the 20 MB limit."
            }
            if ((media.width ?: 0) < MediaRules.MIN_FACE_EDGE || (media.height ?: 0) < MediaRules.MIN_FACE_EDGE) {
                warnings += "Poor quality image. Use a clear face photo above 256px."
            }

            if (errors.isEmpty()) {
                when (val outcome = detectFaces(uri)) {
                    is FaceDetectionOutcome.Success -> when (outcome.faceCount) {
                        0 -> errors += "No face detected."
                        1 -> Unit
                        else -> errors += "Multiple faces detected. Upload one face only."
                    }
                    is FaceDetectionOutcome.Failed -> {
                        errors += "Face detection failed. Try a clear JPG, PNG, or WEBP photo."
                        warnings += outcome.reason
                    }
                }
            }

            MediaValidationResult(media = media, errors = errors, warnings = warnings)
        }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun readFileInfo(uri: Uri): SelectedMedia {
        var displayName = uri.lastPathSegment ?: "Selected media"
        var sizeBytes = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex) ?: displayName
                    if (sizeIndex >= 0) sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        return SelectedMedia(
            uri = uri,
            displayName = displayName,
            mimeType = context.contentResolver.getType(uri) ?: inferMime(uri),
            sizeBytes = sizeBytes
        )
    }

    private fun readImageInfo(uri: Uri, info: SelectedMedia): SelectedMedia {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        }
        return info.copy(
            width = options.outWidth.takeIf { it > 0 },
            height = options.outHeight.takeIf { it > 0 }
        )
    }

    private fun readVideoInfo(uri: Uri, info: SelectedMedia): SelectedMedia {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            info.copy(
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            )
        } finally {
            retriever.release()
        }
    }

    private suspend fun detectFaces(uri: Uri): FaceDetectionOutcome {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        val detector = runCatching { FaceDetection.getClient(options) }.getOrElse {
            return FaceDetectionOutcome.Failed(it.message ?: "Face detector could not start.")
        }
        var bitmap: Bitmap? = null
        return try {
            bitmap = decodeScaledBitmap(uri, MAX_FACE_DETECTION_EDGE)
                ?: return FaceDetectionOutcome.Failed("Image could not be decoded.")
            val image = InputImage.fromBitmap(bitmap, 0)
            FaceDetectionOutcome.Success(detector.process(image).await().size)
        } catch (throwable: Throwable) {
            FaceDetectionOutcome.Failed(throwable.message ?: "ML Kit could not analyze this image.")
        } finally {
            bitmap?.recycle()
            detector.close()
        }
    }

    private fun decodeScaledBitmap(uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val sampleSize = calculateSampleSize(width, height, maxEdge)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= maxEdge || sampledHeight / 2 >= maxEdge) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize
    }

    private fun inferMime(uri: Uri): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString()).orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
    }

    private fun matchesMime(actual: String, expected: String): Boolean {
        if (expected.endsWith("/*")) return actual.startsWith(expected.substringBefore('/'))
        return actual.equals(expected, ignoreCase = true)
    }

    private sealed interface FaceDetectionOutcome {
        data class Success(val faceCount: Int) : FaceDetectionOutcome
        data class Failed(val reason: String) : FaceDetectionOutcome
    }

    private companion object {
        private const val MAX_FACE_DETECTION_EDGE = 1024
        private val supportedFaceMimeTypes = setOf("image/jpeg", "image/png", "image/webp")
    }
}

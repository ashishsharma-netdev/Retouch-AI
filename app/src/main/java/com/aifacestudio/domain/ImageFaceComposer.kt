package com.aifacestudio.domain

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

data class GeneratedImageResult(
    val uri: Uri,
    val sizeBytes: Long
)

@Singleton
class ImageFaceComposer @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    suspend fun compose(projectId: String, sourceUri: String, faceUri: String): GeneratedImageResult =
        withContext(Dispatchers.Default) {
            val sourceBitmap = decodeScaledBitmap(Uri.parse(sourceUri), MAX_SOURCE_EDGE)
                ?: error("Source image could not be decoded.")
            val faceBitmap = decodeScaledBitmap(Uri.parse(faceUri), MAX_FACE_EDGE)
                ?: error("Face image could not be decoded.")

            try {
                val sourceFace = detectLargestFace(sourceBitmap)
                    ?: error("No face detected in source image.")
                val replacementFace = detectLargestFace(faceBitmap)
                    ?: error("No face detected in replacement face image.")

                val output = renderReplacement(
                    source = sourceBitmap,
                    face = faceBitmap,
                    sourceFace = sourceFace,
                    replacementFace = replacementFace
                )
                val outputFile = saveOutput(projectId, output)
                GeneratedImageResult(
                    uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile),
                    sizeBytes = outputFile.length()
                )
            } finally {
                sourceBitmap.recycle()
                faceBitmap.recycle()
            }
        }

    private suspend fun detectLargestFace(bitmap: Bitmap): Rect? {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .build()
        val detector = FaceDetection.getClient(options)
        return try {
            detector.process(InputImage.fromBitmap(bitmap, 0))
                .await()
                .mapNotNull { clampRect(it.boundingBox, bitmap.width, bitmap.height) }
                .maxByOrNull { it.width() * it.height() }
        } finally {
            detector.close()
        }
    }

    private fun renderReplacement(
        source: Bitmap,
        face: Bitmap,
        sourceFace: Rect,
        replacementFace: Rect
    ): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val targetRect = expandRect(sourceFace, source.width, source.height, widthScale = 1.18f, heightScale = 1.26f)
        val faceCropRect = expandRect(replacementFace, face.width, face.height, widthScale = 1.28f, heightScale = 1.34f)
        val faceCrop = Bitmap.createBitmap(face, faceCropRect.left, faceCropRect.top, faceCropRect.width(), faceCropRect.height())
        val scaledFace = Bitmap.createScaledBitmap(faceCrop, targetRect.width(), targetRect.height(), true)

        try {
            Canvas(output).apply {
                val saved = save()
                val oval = RectF(targetRect)
                val maskPath = Path().apply { addOval(oval, Path.Direction.CW) }
                clipPath(maskPath)
                drawBitmap(scaledFace, targetRect.left.toFloat(), targetRect.top.toFloat(), imagePaint)
                restoreToCount(saved)

                val featherPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = max(3f, output.width * 0.004f)
                    color = Color.argb(38, 255, 255, 255)
                }
                drawOval(oval, featherPaint)
                drawAiGeneratedLabel(output.width, output.height)
            }
        } finally {
            faceCrop.recycle()
            scaledFace.recycle()
        }

        return output
    }

    private fun Canvas.drawAiGeneratedLabel(width: Int, height: Int) {
        val label = "AI GENERATED"
        val textSize = max(26f, width * 0.024f)
        val padding = max(12f, width * 0.012f)
        val margin = max(14f, width * 0.014f)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            this.textSize = textSize
            typeface = Typeface.DEFAULT_BOLD
        }
        val textBounds = Rect()
        textPaint.getTextBounds(label, 0, label.length, textBounds)
        val boxRight = width - margin
        val boxBottom = height - margin
        val boxLeft = boxRight - textBounds.width() - padding * 2
        val boxTop = boxBottom - textBounds.height() - padding * 2
        val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(172, 0, 0, 0)
        }

        drawRoundRect(RectF(boxLeft, boxTop, boxRight, boxBottom), 10f, 10f, backgroundPaint)
        drawText(label, boxLeft + padding, boxBottom - padding, textPaint)
    }

    private fun saveOutput(projectId: String, bitmap: Bitmap): File {
        val outputDir = File(context.cacheDir, GENERATED_DIR).apply { mkdirs() }
        val outputFile = File(outputDir, "ai_generated_$projectId.jpg")
        outputFile.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        bitmap.recycle()
        return outputFile
    }

    private fun decodeScaledBitmap(uri: Uri, maxEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(width, height, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
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

    private fun expandRect(rect: Rect, bitmapWidth: Int, bitmapHeight: Int, widthScale: Float, heightScale: Float): Rect {
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        val newWidth = max(1, (rect.width() * widthScale).roundToInt())
        val newHeight = max(1, (rect.height() * heightScale).roundToInt())
        val left = centerX - newWidth / 2
        val top = centerY - newHeight / 2
        return clampRect(Rect(left, top, left + newWidth, top + newHeight), bitmapWidth, bitmapHeight)
            ?: Rect(0, 0, bitmapWidth, bitmapHeight)
    }

    private fun clampRect(rect: Rect, bitmapWidth: Int, bitmapHeight: Int): Rect? {
        val left = rect.left.coerceIn(0, bitmapWidth - 1)
        val top = rect.top.coerceIn(0, bitmapHeight - 1)
        val right = rect.right.coerceIn(left + 1, bitmapWidth)
        val bottom = rect.bottom.coerceIn(top + 1, bitmapHeight)
        return Rect(left, top, right, bottom).takeIf { it.width() > 0 && it.height() > 0 }
    }

    private companion object {
        private const val GENERATED_DIR = "generated"
        private const val JPEG_QUALITY = 92
        private const val MAX_SOURCE_EDGE = 2048
        private const val MAX_FACE_EDGE = 1024

        private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
}

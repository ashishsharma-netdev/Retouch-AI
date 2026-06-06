package com.aifacestudio.domain

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.aifacestudio.BuildConfig
import com.aifacestudio.data.local.MediaFileEntity
import com.aifacestudio.data.local.ProjectEntity
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.remote.FaceMapping
import com.aifacestudio.data.remote.ProcessingApi
import com.aifacestudio.data.remote.ProcessingJobRequest
import com.aifacestudio.data.remote.ProcessingJobResponse
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class CloudProcessingResult(
    val uri: Uri,
    val sizeBytes: Long,
    val remoteProjectId: String
)

@Singleton
class CloudProcessingClient @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val processingApi: ProcessingApi,
    private val moshi: Moshi,
    private val okHttpClient: OkHttpClient
) {
    suspend fun process(
        project: ProjectEntity,
        source: MediaFileEntity,
        faces: List<MediaFileEntity>,
        onProgress: suspend (Int, String) -> Unit
    ): CloudProcessingResult = withContext(Dispatchers.IO) {
        require(project.consentAccepted) { "Consent confirmation is required before cloud processing." }
        require(faces.isNotEmpty()) { "At least one replacement face image is required." }

        onProgress(8, "Uploading Files")
        val response = processingApi.createProcessingJob(
            source = mediaPart("source", source),
            face = mediaPart("face", faces.first()),
            faces = faces.drop(1).take(MAX_REPLACEMENT_FACES - 1).map { mediaPart("faces", it) },
            request = requestBody(project, faces.size)
        )
        onProgress(response.progress, response.statusMessage)

        try {
            val completed = pollUntilComplete(response, onProgress)
            val resultUrl = completed.signedDownloadUrl ?: completed.resultUrl
                ?: error("Backend completed without a result URL.")
            onProgress(95, "Downloading Result")

            val outputFile = downloadResult(project, resultUrl)
            CloudProcessingResult(
                uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outputFile),
                sizeBytes = outputFile.length(),
                remoteProjectId = completed.projectId
            )
        } catch (cancellation: CancellationException) {
            withContext(NonCancellable) {
                runCatching { cancel(response.projectId) }
            }
            throw cancellation
        }
    }

    suspend fun cancel(projectId: String) {
        withContext(Dispatchers.IO) {
            processingApi.cancelProcessingJob(projectId)
        }
    }

    private suspend fun pollUntilComplete(
        initial: ProcessingJobResponse,
        onProgress: suspend (Int, String) -> Unit
    ): ProcessingJobResponse {
        var response = initial
        repeat(MAX_POLL_ATTEMPTS) {
            when (response.status.lowercase()) {
                "completed" -> return response
                "failed" -> error(response.statusMessage)
                "canceled", "cancelled" -> error("Cloud processing was canceled.")
            }
            delay(POLL_INTERVAL_MS)
            response = processingApi.getProcessingJob(response.projectId)
            onProgress(response.progress, response.statusMessage)
        }
        error("Cloud processing timed out.")
    }

    private fun requestBody(project: ProjectEntity, replacementFaceCount: Int): RequestBody {
        val request = ProcessingJobRequest(
            projectId = project.projectId,
            mediaType = project.mediaType,
            imageFormat = project.imageFormat,
            quality = project.quality,
            videoResolution = project.videoResolution,
            fps = project.fps,
            multiFace = project.multiFace,
            faceMappings = if (project.multiFace) {
                List(replacementFaceCount.coerceAtMost(MAX_REPLACEMENT_FACES)) { index ->
                    FaceMapping(sourceFaceIndex = index, replacementIndex = index)
                }
            } else {
                emptyList()
            },
            videoTrimStartMs = project.videoTrimStartMs,
            videoTrimEndMs = project.videoTrimEndMs,
            visibleWatermark = project.visibleWatermark,
            metadataWatermark = project.metadataWatermark,
            userOwnershipDeclared = project.consentAccepted,
            termsAccepted = project.consentAccepted
        )
        val json = moshi.adapter(ProcessingJobRequest::class.java).toJson(request)
        return json.toRequestBody("application/json; charset=utf-8".toMediaType())
    }

    private fun mediaPart(name: String, media: MediaFileEntity): MultipartBody.Part {
        val body = contentUriRequestBody(media)
        return MultipartBody.Part.createFormData(name, safeFileName(media.displayName), body)
    }

    private fun contentUriRequestBody(media: MediaFileEntity): RequestBody {
        val uri = Uri.parse(media.uri)
        val contentType = (media.mimeType ?: context.contentResolver.getType(uri) ?: "application/octet-stream")
            .toMediaTypeOrNull()
        return object : RequestBody() {
            override fun contentType() = contentType

            override fun contentLength(): Long = media.sizeBytes.takeIf { it >= 0 } ?: -1L

            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to read ${media.displayName}." }
                    sink.writeAll(input.source())
                }
            }
        }
    }

    private fun downloadResult(project: ProjectEntity, resultUrl: String): File {
        val request = Request.Builder()
            .url(absoluteUrl(resultUrl))
            .get()
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Result download failed with HTTP ${response.code}.")
            }
            val body = requireNotNull(response.body) { "Backend returned an empty result." }
            val outputDir = File(context.cacheDir, GENERATED_DIR).apply { mkdirs() }
            val outputFile = File(outputDir, "cloud_generated_${project.projectId}.${project.cloudOutputExtension()}")
            body.byteStream().use { input ->
                outputFile.outputStream().use { output -> input.copyTo(output) }
            }
            return outputFile
        }
    }

    private fun absoluteUrl(url: String): String {
        if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
            return url
        }
        return BuildConfig.API_BASE_URL.trimEnd('/') + "/" + url.trimStart('/')
    }

    private fun safeFileName(displayName: String): String =
        displayName.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifBlank { "upload.bin" }

    private fun ProjectEntity.cloudOutputExtension(): String = when (mediaType) {
        MediaType.VIDEO -> "mp4"
        MediaType.GIF -> "gif"
        MediaType.IMAGE -> imageFormat.extension
    }

    private companion object {
        private const val GENERATED_DIR = "generated"
        private const val MAX_POLL_ATTEMPTS = 1_800
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_REPLACEMENT_FACES = 12
    }
}

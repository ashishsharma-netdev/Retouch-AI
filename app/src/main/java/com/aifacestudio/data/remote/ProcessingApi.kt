package com.aifacestudio.data.remote

import com.aifacestudio.data.model.ExportQuality
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.VideoResolution
import com.squareup.moshi.JsonClass
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface ProcessingApi {
    @Multipart
    @POST("v1/projects")
    suspend fun createProcessingJob(
        @Part source: MultipartBody.Part,
        @Part face: MultipartBody.Part,
        @Part faces: List<MultipartBody.Part>,
        @Part("request") request: RequestBody
    ): ProcessingJobResponse

    @GET("v1/projects/{projectId}")
    suspend fun getProcessingJob(@Path("projectId") projectId: String): ProcessingJobResponse

    @POST("v1/projects/{projectId}/cancel")
    suspend fun cancelProcessingJob(@Path("projectId") projectId: String)

    @POST("v1/reports")
    suspend fun reportContent(@Body request: ContentReportRequest)
}

@JsonClass(generateAdapter = true)
data class ProcessingJobRequest(
    val projectId: String,
    val mediaType: MediaType,
    val imageFormat: ImageFormat,
    val quality: ExportQuality,
    val videoResolution: VideoResolution,
    val fps: Int,
    val multiFace: Boolean,
    val faceMappings: List<FaceMapping>,
    val videoTrimStartMs: Long,
    val videoTrimEndMs: Long,
    val visibleWatermark: Boolean,
    val metadataWatermark: Boolean,
    val userOwnershipDeclared: Boolean,
    val termsAccepted: Boolean
)

@JsonClass(generateAdapter = true)
data class FaceMapping(
    val sourceFaceIndex: Int,
    val replacementIndex: Int
)

@JsonClass(generateAdapter = true)
data class ProcessingJobResponse(
    val projectId: String,
    val status: String,
    val progress: Int,
    val statusMessage: String,
    val resultUrl: String?,
    val signedDownloadUrl: String?
)

@JsonClass(generateAdapter = true)
data class ContentReportRequest(
    val projectId: String?,
    val reason: String,
    val detail: String
)

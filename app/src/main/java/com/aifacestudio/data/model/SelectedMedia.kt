package com.aifacestudio.data.model

import android.net.Uri

data class SelectedMedia(
    val uri: Uri,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
)

data class MediaValidationResult(
    val media: SelectedMedia?,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList()
) {
    val isValid: Boolean get() = media != null && errors.isEmpty()
}

data class ProjectDetails(
    val project: com.aifacestudio.data.local.ProjectEntity,
    val mediaFiles: List<com.aifacestudio.data.local.MediaFileEntity>,
    val job: com.aifacestudio.data.local.ProcessingJobEntity?
)

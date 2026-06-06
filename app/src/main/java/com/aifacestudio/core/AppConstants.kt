package com.aifacestudio.core

import com.aifacestudio.data.model.MediaType

object MediaRules {
    const val IMAGE_MAX_BYTES = 20L * 1024L * 1024L
    const val VIDEO_MAX_BYTES = 500L * 1024L * 1024L
    const val GIF_MAX_BYTES = 100L * 1024L * 1024L
    const val VIDEO_MAX_DURATION_MS = 10L * 60L * 1000L
    const val FOUR_K_LONG_EDGE = 4096
    const val MIN_FACE_EDGE = 256

    fun maxBytes(mediaType: MediaType): Long = when (mediaType) {
        MediaType.IMAGE -> IMAGE_MAX_BYTES
        MediaType.VIDEO -> VIDEO_MAX_BYTES
        MediaType.GIF -> GIF_MAX_BYTES
    }

    fun sourceMimeTypes(mediaType: MediaType): Array<String> = when (mediaType) {
        MediaType.IMAGE -> arrayOf("image/jpeg", "image/png", "image/webp")
        MediaType.VIDEO -> arrayOf("video/mp4", "video/quicktime", "video/x-matroska")
        MediaType.GIF -> arrayOf("image/gif")
    }

    fun pickerMimeTypes(mediaType: MediaType): Array<String> = when (mediaType) {
        MediaType.IMAGE -> arrayOf("image/jpeg", "image/png", "image/webp")
        MediaType.VIDEO -> arrayOf("video/mp4", "video/quicktime", "video/x-matroska", "video/*")
        MediaType.GIF -> arrayOf("image/gif")
    }
}

object ProcessingProgress {
    const val KEY_PROJECT_ID = "project_id"
    const val KEY_PROGRESS = "progress"
    const val KEY_STATUS_MESSAGE = "status_message"
    const val UNIQUE_WORK_PREFIX = "face_replace_processing_"
}

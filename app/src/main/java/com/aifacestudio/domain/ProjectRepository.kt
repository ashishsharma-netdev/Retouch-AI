package com.aifacestudio.domain

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aifacestudio.core.ProcessingProgress
import com.aifacestudio.data.analytics.AnalyticsTracker
import com.aifacestudio.data.local.AuditLogDao
import com.aifacestudio.data.local.AuditLogEntity
import com.aifacestudio.data.local.MediaFileDao
import com.aifacestudio.data.local.MediaFileEntity
import com.aifacestudio.data.local.ProcessingJobDao
import com.aifacestudio.data.local.ProcessingJobEntity
import com.aifacestudio.data.local.ProjectDao
import com.aifacestudio.data.local.ProjectEntity
import com.aifacestudio.data.model.ExportQuality
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaRole
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.ProjectDetails
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.data.model.SelectedMedia
import com.aifacestudio.data.model.VideoResolution
import com.aifacestudio.worker.ProcessingWorker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val projectDao: ProjectDao,
    private val mediaFileDao: MediaFileDao,
    private val processingJobDao: ProcessingJobDao,
    private val auditLogDao: AuditLogDao,
    private val workManager: WorkManager,
    private val analyticsTracker: AnalyticsTracker,
    private val cloudProcessingClient: CloudProcessingClient
) {
    fun observeRecentProjects(limit: Int = 8): Flow<List<ProjectEntity>> =
        projectDao.observeRecentProjects(limit)

    fun observeAllProjects(): Flow<List<ProjectEntity>> =
        projectDao.observeAllProjects()

    fun observeProjectDetails(projectId: String): Flow<ProjectDetails?> =
        combine(
            projectDao.observeProject(projectId),
            mediaFileDao.observeMediaFiles(projectId),
            processingJobDao.observeLatestJob(projectId)
        ) { project, mediaFiles, job ->
            project?.let { ProjectDetails(it, mediaFiles, job) }
        }

    suspend fun getProject(projectId: String): ProjectEntity? = projectDao.getProject(projectId)

    suspend fun createDraft(mediaType: MediaType, source: SelectedMedia, faces: List<SelectedMedia>): String {
        require(faces.isNotEmpty()) { "At least one face image is required." }
        val projectId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val project = ProjectEntity(
            projectId = projectId,
            title = "${mediaType.noun} replacement",
            createdAt = now,
            mediaType = mediaType,
            sourceUri = source.uri.toString(),
            faceUri = faces.first().uri.toString(),
            multiFace = faces.size > 1
        )
        projectDao.upsertProject(project)
        mediaFileDao.upsertMediaFiles(
            listOf(source.toEntity(projectId, MediaRole.SOURCE, sortOrder = 0)) +
                faces.take(MAX_REPLACEMENT_FACES).mapIndexed { index, face ->
                    face.toEntity(projectId, MediaRole.FACE, sortOrder = index)
                }
        )
        auditLogDao.insert(
            AuditLogEntity(
                auditLogId = UUID.randomUUID().toString(),
                projectId = projectId,
                eventType = "draft_created",
                detail = "User selected source and ${faces.size} replacement face input(s).",
                createdAt = now
            )
        )
        analyticsTracker.track("upload_inputs_validated", mapOf("media_type" to mediaType.name))
        return projectId
    }

    suspend fun updateSettings(
        projectId: String,
        imageFormat: ImageFormat,
        quality: ExportQuality,
        videoResolution: VideoResolution,
        fps: Int,
        multiFace: Boolean,
        videoTrimStartMs: Long,
        videoTrimEndMs: Long,
        visibleWatermark: Boolean,
        metadataWatermark: Boolean,
        consentAccepted: Boolean
    ) {
        projectDao.updateSettings(
            projectId = projectId,
            imageFormat = imageFormat,
            quality = quality,
            videoResolution = videoResolution,
            fps = fps,
            multiFace = multiFace,
            videoTrimStartMs = videoTrimStartMs,
            videoTrimEndMs = videoTrimEndMs,
            visibleWatermark = visibleWatermark,
            metadataWatermark = metadataWatermark,
            consentAccepted = consentAccepted
        )
    }

    suspend fun enqueueProcessing(projectId: String): UUID {
        val workerId = UUID.randomUUID()
        val now = System.currentTimeMillis()
        processingJobDao.upsertJob(
            ProcessingJobEntity(
                jobId = workerId.toString(),
                projectId = projectId,
                workerId = workerId.toString(),
                progress = 0,
                status = ProjectStatus.PROCESSING,
                statusMessage = "Queued",
                createdAt = now,
                updatedAt = now
            )
        )
        projectDao.updateStatus(projectId, ProjectStatus.PROCESSING)
        val request = OneTimeWorkRequestBuilder<ProcessingWorker>()
            .setId(workerId)
            .setInputData(workDataOf(ProcessingProgress.KEY_PROJECT_ID to projectId))
            .addTag(projectId)
            .build()
        workManager.enqueueUniqueWork(
            ProcessingProgress.UNIQUE_WORK_PREFIX + projectId,
            ExistingWorkPolicy.REPLACE,
            request
        )
        analyticsTracker.track("processing_started", mapOf("project_id" to projectId))
        return workerId
    }

    suspend fun cancelProcessing(projectId: String) {
        workManager.cancelUniqueWork(ProcessingProgress.UNIQUE_WORK_PREFIX + projectId)
        runCatching { cloudProcessingClient.cancel(projectId) }
        projectDao.updateStatus(projectId, ProjectStatus.CANCELED)
        processingJobDao.getLatestJob(projectId)?.let { job ->
            processingJobDao.updateJob(
                jobId = job.jobId,
                progress = job.progress,
                status = ProjectStatus.CANCELED,
                statusMessage = "Canceled",
                updatedAt = System.currentTimeMillis(),
                errorMessage = null
            )
        }
        analyticsTracker.track("processing_canceled", mapOf("project_id" to projectId))
    }

    suspend fun deleteProject(projectId: String) {
        projectDao.deleteProject(projectId)
        analyticsTracker.track("project_deleted", mapOf("project_id" to projectId))
    }

    suspend fun duplicateProject(projectId: String): String? {
        val project = projectDao.getProject(projectId) ?: return null
        val mediaFiles = mediaFileDao.getMediaFiles(projectId)
            .filter { it.role != MediaRole.OUTPUT }
        val duplicateId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        projectDao.upsertProject(
            project.copy(
                projectId = duplicateId,
                title = "${project.title} copy",
                createdAt = now,
                outputUri = null,
                status = ProjectStatus.DRAFT,
                processingTimeMs = null,
                outputSizeBytes = null,
                consentAccepted = false
            )
        )
        mediaFileDao.upsertMediaFiles(
            mediaFiles.map {
                it.copy(
                    mediaFileId = UUID.randomUUID().toString(),
                    projectId = duplicateId
                )
            }
        )
        auditLogDao.insert(
            AuditLogEntity(
                auditLogId = UUID.randomUUID().toString(),
                projectId = duplicateId,
                eventType = "project_duplicated",
                detail = "Duplicated from $projectId.",
                createdAt = now
            )
        )
        analyticsTracker.track("project_duplicated", mapOf("project_id" to projectId))
        return duplicateId
    }

    private fun SelectedMedia.toEntity(projectId: String, role: MediaRole, sortOrder: Int): MediaFileEntity =
        MediaFileEntity(
            mediaFileId = UUID.randomUUID().toString(),
            projectId = projectId,
            role = role,
            uri = uri.toString(),
            displayName = displayName,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            sortOrder = sortOrder,
            width = width,
            height = height,
            durationMs = durationMs
        )

    private companion object {
        private const val MAX_REPLACEMENT_FACES = 12
    }
}

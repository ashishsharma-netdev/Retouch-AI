package com.aifacestudio.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aifacestudio.R
import com.aifacestudio.core.ProcessingProgress
import com.aifacestudio.data.local.AppDatabase
import com.aifacestudio.data.local.AuditLogEntity
import com.aifacestudio.data.model.MediaRole
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.domain.CloudProcessingClient
import com.aifacestudio.domain.ImageFaceComposer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.UUID

@HiltWorker
class ProcessingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val database: AppDatabase,
    private val imageFaceComposer: ImageFaceComposer,
    private val cloudProcessingClient: CloudProcessingClient
) : CoroutineWorker(appContext, params) {
    private val projectDao = database.projectDao()
    private val mediaFileDao = database.mediaFileDao()
    private val processingJobDao = database.processingJobDao()
    private val auditLogDao = database.auditLogDao()

    override suspend fun doWork(): Result {
        val projectId = inputData.getString(ProcessingProgress.KEY_PROJECT_ID) ?: return Result.failure()
        val project = projectDao.getProject(projectId) ?: return Result.failure()
        val job = processingJobDao.getLatestJob(projectId) ?: return Result.failure()
        val startedAt = System.currentTimeMillis()

        return runCatching {
            createNotificationChannel()
            projectDao.updateStatus(projectId, ProjectStatus.PROCESSING)

            val setupSteps = setupProcessingSteps(project.mediaType)
            for ((progress, message, pauseMs) in setupSteps) {
                if (isStopped) {
                    processingJobDao.updateJob(
                        jobId = job.jobId,
                        progress = progress,
                        status = ProjectStatus.CANCELED,
                        statusMessage = "Canceled",
                        updatedAt = System.currentTimeMillis(),
                        errorMessage = null
                    )
                    projectDao.updateStatus(projectId, ProjectStatus.CANCELED)
                    return Result.failure()
                }
                updateProgress(job.jobId, projectId, progress, message)
                delay(pauseMs)
            }

            val mediaFiles = mediaFileDao.getMediaFiles(projectId)
            val source = mediaFiles.firstOrNull { it.role == MediaRole.SOURCE }
            val faces = mediaFiles.filter { it.role == MediaRole.FACE }
            var cloudFailure: Throwable? = null
            val cloudSupported = project.mediaType == MediaType.IMAGE ||
                project.mediaType == MediaType.VIDEO ||
                project.mediaType == MediaType.GIF
            val cloudResult = if (cloudSupported && source != null && faces.isNotEmpty()) {
                runCatching {
                    cloudProcessingClient.process(project, source, faces) { cloudProgress, cloudMessage ->
                        val mappedProgress = 30 + (cloudProgress.coerceIn(0, 100) * 60 / 100)
                        updateProgress(job.jobId, projectId, mappedProgress, cloudMessage)
                    }
                }.onFailure { throwable ->
                    cloudFailure = throwable
                    Log.w(TAG, "Cloud processing unavailable for $projectId: ${throwable.message}")
                }.getOrNull()
            } else {
                null
            }
            val localImage = if (cloudResult == null && project.mediaType == MediaType.IMAGE) {
                updateProgress(job.jobId, projectId, 45, "Running Local Image Processor")
                imageFaceComposer.compose(
                    projectId = projectId,
                    sourceUri = project.sourceUri,
                    faceUri = project.faceUri
                ).also {
                    updateProgress(job.jobId, projectId, 90, "Finalizing Result")
                }
            } else {
                null
            }
            if (cloudResult == null && localImage == null) {
                if (project.mediaType == MediaType.VIDEO || project.mediaType == MediaType.GIF) {
                    throw cloudFailure ?: IllegalStateException("${project.mediaType.noun} cloud processing did not return a result.")
                }
                runMockMediaCompletion(job.jobId, projectId)
            }

            val outputUri = cloudResult?.uri?.toString() ?: localImage?.uri?.toString() ?: project.sourceUri
            val outputSize = cloudResult?.sizeBytes ?: localImage?.sizeBytes ?: source?.sizeBytes?.takeIf { it > 0 } ?: 0L
            val duration = System.currentTimeMillis() - startedAt
            projectDao.markCompleted(
                projectId = projectId,
                status = ProjectStatus.COMPLETED,
                outputUri = outputUri,
                processingTimeMs = duration,
                outputSizeBytes = outputSize
            )
            processingJobDao.updateJob(
                jobId = job.jobId,
                progress = 100,
                status = ProjectStatus.COMPLETED,
                statusMessage = "Completed",
                updatedAt = System.currentTimeMillis(),
                errorMessage = null
            )
            auditLogDao.insert(
                AuditLogEntity(
                    auditLogId = UUID.randomUUID().toString(),
                    projectId = projectId,
                    eventType = "processing_completed",
                    detail = when {
                        cloudResult != null -> "Cloud backend completed and returned a generated result."
                        localImage != null -> "Local demo image compositor completed with AI-generated label."
                        else -> "Mock media completion used for unsupported backend media type."
                    },
                    createdAt = System.currentTimeMillis()
                )
            )
            notifyProgress(100, "Completed")
            Log.d(TAG, "Project $projectId completed in ${duration}ms")
            Result.success(workDataOf(ProcessingProgress.KEY_PROJECT_ID to projectId))
        }.getOrElse { throwable ->
            if (throwable is CancellationException || isStopped) {
                processingJobDao.updateJob(
                    jobId = job.jobId,
                    progress = 0,
                    status = ProjectStatus.CANCELED,
                    statusMessage = "Canceled",
                    updatedAt = System.currentTimeMillis(),
                    errorMessage = null
                )
                projectDao.updateStatus(projectId, ProjectStatus.CANCELED)
                return Result.failure(workDataOf(ProcessingProgress.KEY_STATUS_MESSAGE to "Canceled"))
            }
            val reason = throwable.message ?: throwable::class.java.simpleName
            Log.e(TAG, "Project $projectId failed", throwable)
            processingJobDao.updateJob(
                jobId = job.jobId,
                progress = 0,
                status = ProjectStatus.FAILED,
                statusMessage = "Processing failed",
                updatedAt = System.currentTimeMillis(),
                errorMessage = reason
            )
            projectDao.updateStatus(projectId, ProjectStatus.FAILED)
            auditLogDao.insert(
                AuditLogEntity(
                    auditLogId = UUID.randomUUID().toString(),
                    projectId = projectId,
                    eventType = "processing_failed",
                    detail = reason,
                    createdAt = System.currentTimeMillis()
                )
            )
            Result.failure(workDataOf(ProcessingProgress.KEY_STATUS_MESSAGE to reason))
        }
    }

    private fun setupProcessingSteps(mediaType: MediaType): List<ProgressStep> {
        val baseDelay = when (mediaType) {
            MediaType.IMAGE -> 350L
            MediaType.GIF -> 500L
            MediaType.VIDEO -> 850L
        }
        return listOf(
            ProgressStep(0, "Uploading Files", baseDelay),
            ProgressStep(10, "Analyzing Faces", baseDelay),
            ProgressStep(25, "Preparing Frames", baseDelay)
        )
    }

    private suspend fun runMockMediaCompletion(jobId: String, projectId: String) {
        val steps = listOf(
            ProgressStep(40, "Running AI Model", 500L),
            ProgressStep(60, "Rendering Output", 500L),
            ProgressStep(75, "Applying AI Generated Label", 500L),
            ProgressStep(90, "Finalizing Result", 500L)
        )
        for ((progress, message, pauseMs) in steps) {
            updateProgress(jobId, projectId, progress, message)
            delay(pauseMs)
        }
    }

    private suspend fun updateProgress(jobId: String, projectId: String, progress: Int, message: String) {
        setProgress(workDataOf(ProcessingProgress.KEY_PROGRESS to progress, ProcessingProgress.KEY_STATUS_MESSAGE to message))
        processingJobDao.updateJob(
            jobId = jobId,
            progress = progress,
            status = ProjectStatus.PROCESSING,
            statusMessage = message,
            updatedAt = System.currentTimeMillis(),
            errorMessage = null
        )
        notifyProgress(progress, message)
        Log.d(TAG, "Project $projectId progress=$progress message=$message")
    }

    private fun notifyProgress(progress: Int, message: String) {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Processing media")
            .setContentText("$progress% Completed - $message")
            .setOnlyAlertOnce(true)
            .setOngoing(progress < 100)
            .setProgress(100, progress, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel Processing", cancelIntent)
            .build()

        maybeNotify(notification)
    }

    private fun maybeNotify(notification: android.app.Notification) {
        val canNotify = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (canNotify) {
            runCatching {
                NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.processing_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private data class ProgressStep(val progress: Int, val message: String, val pauseMs: Long)

    companion object {
        private const val TAG = "ProcessingWorker"
        private const val CHANNEL_ID = "processing_updates"
        private const val NOTIFICATION_ID = 7301
    }
}

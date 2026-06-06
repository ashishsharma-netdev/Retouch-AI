package com.aifacestudio.domain

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.aifacestudio.data.local.AuditLogDao
import com.aifacestudio.data.local.AuditLogEntity
import com.aifacestudio.data.local.ExportHistoryDao
import com.aifacestudio.data.local.ExportHistoryEntity
import com.aifacestudio.data.local.ProjectEntity
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.ShareTarget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exportHistoryDao: ExportHistoryDao,
    private val auditLogDao: AuditLogDao
) {
    suspend fun saveToGallery(project: ProjectEntity, destination: String): Uri =
        withContext(Dispatchers.IO) {
            val sourceUri = Uri.parse(project.outputUri ?: project.sourceUri)
            val mimeType = project.exportMimeType()
            val displayName = project.exportDisplayName()
            val collection = when (project.mediaType) {
                MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaType.IMAGE, MediaType.GIF -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.TITLE, "AI Generated - ${project.title}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, project.relativePath())
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val outputUri = requireNotNull(context.contentResolver.insert(collection, values)) {
                "Unable to create gallery item."
            }
            context.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Unable to read processed media." }
                context.contentResolver.openOutputStream(outputUri).use { output ->
                    requireNotNull(output) { "Unable to write processed media." }
                    input.copyTo(output)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(outputUri, values, null, null)
            }
            recordExport(project, destination, outputUri.toString())
            outputUri
        }

    suspend fun recordShare(project: ProjectEntity, target: ShareTarget) {
        recordExport(project, target.label, project.outputUri ?: project.sourceUri)
    }

    fun buildShareIntent(project: ProjectEntity, target: ShareTarget): Intent {
        val uri = Uri.parse(project.outputUri ?: project.sourceUri)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(project.exportMimeType())
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "AI generated media created with visible labeling.")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (target == ShareTarget.EMAIL) {
            intent.putExtra(Intent.EXTRA_SUBJECT, "AI generated media export")
        } else if (target.packageName != null) {
            intent.setPackage(target.packageName)
        }
        return Intent.createChooser(intent, "Share AI generated media")
    }

    private suspend fun recordExport(project: ProjectEntity, destination: String, outputUri: String) {
        val now = System.currentTimeMillis()
        exportHistoryDao.insert(
            ExportHistoryEntity(
                exportId = UUID.randomUUID().toString(),
                projectId = project.projectId,
                destination = destination,
                outputUri = outputUri,
                exportedAt = now,
                aiGeneratedLabel = true,
                metadataWatermark = project.metadataWatermark
            )
        )
        auditLogDao.insert(
            AuditLogEntity(
                auditLogId = UUID.randomUUID().toString(),
                projectId = project.projectId,
                eventType = "export",
                detail = "Exported to $destination with AI generated label.",
                createdAt = now
            )
        )
    }

    private fun ProjectEntity.exportMimeType(): String = when (mediaType) {
        MediaType.IMAGE -> imageFormat.mimeType
        MediaType.VIDEO -> "video/mp4"
        MediaType.GIF -> "image/gif"
    }

    private fun ProjectEntity.exportDisplayName(): String {
        val extension = when (mediaType) {
            MediaType.IMAGE -> imageFormat.extension
            MediaType.VIDEO -> "mp4"
            MediaType.GIF -> "gif"
        }
        return "AI_GENERATED_${projectId.take(8)}.$extension"
    }

    private fun ProjectEntity.relativePath(): String = when (mediaType) {
        MediaType.VIDEO -> "Movies/AI Face Studio"
        MediaType.IMAGE, MediaType.GIF -> "Pictures/AI Face Studio"
    }
}

package com.aifacestudio.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aifacestudio.data.model.ExportQuality
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaRole
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.data.model.SubscriptionPlan
import com.aifacestudio.data.model.VideoResolution

@Entity(tableName = "users")
data class UserEntity(
    @PrimaryKey val userId: String,
    val displayName: String,
    val email: String?,
    val createdAt: Long,
    val subscriptionPlan: SubscriptionPlan = SubscriptionPlan.FREE,
    val storageUsedBytes: Long = 0L,
    val privacySettingsJson: String = "{}"
)

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val projectId: String,
    val title: String,
    val createdAt: Long,
    val mediaType: MediaType,
    val sourceUri: String,
    val faceUri: String,
    val outputUri: String? = null,
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val processingTimeMs: Long? = null,
    val outputSizeBytes: Long? = null,
    val imageFormat: ImageFormat = ImageFormat.JPG,
    val quality: ExportQuality = ExportQuality.STANDARD,
    val videoResolution: VideoResolution = VideoResolution.P1080,
    val fps: Int = 30,
    val multiFace: Boolean = false,
    val videoTrimStartMs: Long = 0L,
    val videoTrimEndMs: Long = 0L,
    val visibleWatermark: Boolean = true,
    val metadataWatermark: Boolean = true,
    val consentAccepted: Boolean = false
)

@Entity(
    tableName = "media_files",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class MediaFileEntity(
    @PrimaryKey val mediaFileId: String,
    val projectId: String,
    val role: MediaRole,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val sortOrder: Int = 0,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
)

@Entity(
    tableName = "processing_jobs",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class ProcessingJobEntity(
    @PrimaryKey val jobId: String,
    val projectId: String,
    val workerId: String?,
    val progress: Int,
    val status: ProjectStatus,
    val statusMessage: String,
    val createdAt: Long,
    val updatedAt: Long,
    val errorMessage: String? = null
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val subscriptionId: String,
    val userId: String,
    val plan: SubscriptionPlan,
    val monthlyProcessingLimit: Int?,
    val usedThisMonth: Int,
    val renewsAt: Long?
)

@Entity(tableName = "notifications")
data class NotificationEntity(
    @PrimaryKey val notificationId: String,
    val userId: String?,
    val title: String,
    val body: String,
    val createdAt: Long,
    val read: Boolean = false
)

@Entity(
    tableName = "export_history",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["projectId"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class ExportHistoryEntity(
    @PrimaryKey val exportId: String,
    val projectId: String,
    val destination: String,
    val outputUri: String,
    val exportedAt: Long,
    val aiGeneratedLabel: Boolean,
    val metadataWatermark: Boolean
)

@Entity(tableName = "audit_logs")
data class AuditLogEntity(
    @PrimaryKey val auditLogId: String,
    val projectId: String?,
    val eventType: String,
    val detail: String,
    val createdAt: Long
)

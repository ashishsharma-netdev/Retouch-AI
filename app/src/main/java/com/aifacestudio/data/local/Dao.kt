package com.aifacestudio.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.aifacestudio.data.model.ProjectStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentProjects(limit: Int): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE projectId = :projectId")
    fun observeProject(projectId: String): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE projectId = :projectId")
    suspend fun getProject(projectId: String): ProjectEntity?

    @Upsert
    suspend fun upsertProject(project: ProjectEntity)

    @Query("UPDATE projects SET status = :status WHERE projectId = :projectId")
    suspend fun updateStatus(projectId: String, status: ProjectStatus)

    @Query(
        """
        UPDATE projects
        SET imageFormat = :imageFormat,
            quality = :quality,
            videoResolution = :videoResolution,
            fps = :fps,
            multiFace = :multiFace,
            videoTrimStartMs = :videoTrimStartMs,
            videoTrimEndMs = :videoTrimEndMs,
            visibleWatermark = :visibleWatermark,
            metadataWatermark = :metadataWatermark,
            consentAccepted = :consentAccepted
        WHERE projectId = :projectId
        """
    )
    suspend fun updateSettings(
        projectId: String,
        imageFormat: com.aifacestudio.data.model.ImageFormat,
        quality: com.aifacestudio.data.model.ExportQuality,
        videoResolution: com.aifacestudio.data.model.VideoResolution,
        fps: Int,
        multiFace: Boolean,
        videoTrimStartMs: Long,
        videoTrimEndMs: Long,
        visibleWatermark: Boolean,
        metadataWatermark: Boolean,
        consentAccepted: Boolean
    )

    @Query(
        """
        UPDATE projects
        SET status = :status,
            outputUri = :outputUri,
            processingTimeMs = :processingTimeMs,
            outputSizeBytes = :outputSizeBytes
        WHERE projectId = :projectId
        """
    )
    suspend fun markCompleted(
        projectId: String,
        status: ProjectStatus,
        outputUri: String,
        processingTimeMs: Long,
        outputSizeBytes: Long
    )

    @Query("DELETE FROM projects WHERE projectId = :projectId")
    suspend fun deleteProject(projectId: String)
}

@Dao
interface MediaFileDao {
    @Query("SELECT * FROM media_files WHERE projectId = :projectId ORDER BY role, sortOrder")
    fun observeMediaFiles(projectId: String): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE projectId = :projectId ORDER BY role, sortOrder")
    suspend fun getMediaFiles(projectId: String): List<MediaFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaFiles(mediaFiles: List<MediaFileEntity>)
}

@Dao
interface ProcessingJobDao {
    @Query("SELECT * FROM processing_jobs WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT 1")
    fun observeLatestJob(projectId: String): Flow<ProcessingJobEntity?>

    @Query("SELECT * FROM processing_jobs WHERE projectId = :projectId ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestJob(projectId: String): ProcessingJobEntity?

    @Upsert
    suspend fun upsertJob(job: ProcessingJobEntity)

    @Query(
        """
        UPDATE processing_jobs
        SET progress = :progress,
            status = :status,
            statusMessage = :statusMessage,
            updatedAt = :updatedAt,
            errorMessage = :errorMessage
        WHERE jobId = :jobId
        """
    )
    suspend fun updateJob(
        jobId: String,
        progress: Int,
        status: ProjectStatus,
        statusMessage: String,
        updatedAt: Long,
        errorMessage: String?
    )
}

@Dao
interface ExportHistoryDao {
    @Query("SELECT * FROM export_history WHERE projectId = :projectId ORDER BY exportedAt DESC")
    fun observeProjectExports(projectId: String): Flow<List<ExportHistoryEntity>>

    @Query("SELECT * FROM export_history ORDER BY exportedAt DESC")
    fun observeAllExports(): Flow<List<ExportHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(export: ExportHistoryEntity)
}

@Dao
interface AuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: AuditLogEntity)
}

@Dao
interface UserDao {
    @Upsert
    suspend fun upsertUser(user: UserEntity)

    @Query("SELECT * FROM users LIMIT 1")
    fun observeCurrentUser(): Flow<UserEntity?>
}

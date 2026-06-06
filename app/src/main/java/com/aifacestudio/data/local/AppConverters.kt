package com.aifacestudio.data.local

import androidx.room.TypeConverter
import com.aifacestudio.data.model.ExportQuality
import com.aifacestudio.data.model.ImageFormat
import com.aifacestudio.data.model.MediaRole
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.data.model.ProjectStatus
import com.aifacestudio.data.model.SubscriptionPlan
import com.aifacestudio.data.model.VideoResolution

class AppConverters {
    @TypeConverter fun toMediaType(value: String): MediaType = enumValueOf(value)
    @TypeConverter fun fromMediaType(value: MediaType): String = value.name

    @TypeConverter fun toMediaRole(value: String): MediaRole = enumValueOf(value)
    @TypeConverter fun fromMediaRole(value: MediaRole): String = value.name

    @TypeConverter fun toProjectStatus(value: String): ProjectStatus = enumValueOf(value)
    @TypeConverter fun fromProjectStatus(value: ProjectStatus): String = value.name

    @TypeConverter fun toImageFormat(value: String): ImageFormat = enumValueOf(value)
    @TypeConverter fun fromImageFormat(value: ImageFormat): String = value.name

    @TypeConverter fun toExportQuality(value: String): ExportQuality = enumValueOf(value)
    @TypeConverter fun fromExportQuality(value: ExportQuality): String = value.name

    @TypeConverter fun toVideoResolution(value: String): VideoResolution = enumValueOf(value)
    @TypeConverter fun fromVideoResolution(value: VideoResolution): String = value.name

    @TypeConverter fun toSubscriptionPlan(value: String): SubscriptionPlan = enumValueOf(value)
    @TypeConverter fun fromSubscriptionPlan(value: SubscriptionPlan): String = value.name
}

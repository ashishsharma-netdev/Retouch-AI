package com.aifacestudio.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.aifacestudio.BuildConfig
import com.aifacestudio.data.analytics.AnalyticsTracker
import com.aifacestudio.data.analytics.LogcatAnalyticsTracker
import com.aifacestudio.data.local.AppDatabase
import com.aifacestudio.data.remote.ProcessingApi
import com.aifacestudio.data.security.AuthTokenStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "ai_face_studio.db")
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .build()

    @Provides
    fun provideProjectDao(database: AppDatabase) = database.projectDao()

    @Provides
    fun provideMediaFileDao(database: AppDatabase) = database.mediaFileDao()

    @Provides
    fun provideProcessingJobDao(database: AppDatabase) = database.processingJobDao()

    @Provides
    fun provideExportHistoryDao(database: AppDatabase) = database.exportHistoryDao()

    @Provides
    fun provideAuditLogDao(database: AppDatabase) = database.auditLogDao()

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    @Singleton
    fun provideMoshi(): Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    @Provides
    @Singleton
    fun provideOkHttpClient(authTokenStore: AuthTokenStore): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        return OkHttpClient.Builder()
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(15, TimeUnit.MINUTES)
            .addInterceptor { chain ->
                val token = runBlocking { authTokenStore.token.firstOrNull() }
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .header("X-Client", "android")
                    .apply {
                        if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                    }
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .build()
    }

    @Provides
    @Singleton
    fun provideProcessingApi(okHttpClient: OkHttpClient, moshi: Moshi): ProcessingApi {
        require(BuildConfig.DEBUG || BuildConfig.API_BASE_URL.startsWith("https://")) {
            "API_BASE_URL must use HTTPS for media processing."
        }
        return Retrofit.Builder()
            .baseUrl(BuildConfig.API_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(ProcessingApi::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AnalyticsModule {
    @Binds
    @Singleton
    abstract fun bindAnalyticsTracker(impl: LogcatAnalyticsTracker): AnalyticsTracker
}

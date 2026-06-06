package com.aifacestudio.data.security

import com.aifacestudio.core.preferences.AppPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthTokenStore @Inject constructor(
    private val preferences: AppPreferences
) {
    val token: Flow<String?> = preferences.jwtToken

    suspend fun saveToken(token: String?) {
        preferences.setJwtToken(token)
    }
}

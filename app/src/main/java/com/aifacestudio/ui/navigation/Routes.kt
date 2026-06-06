package com.aifacestudio.ui.navigation

import com.aifacestudio.data.model.MediaType

object Routes {
    const val SPLASH = "splash"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val HISTORY = "history"
    const val PROFILE = "profile"
    const val UPLOAD = "upload/{mediaType}"
    const val PREVIEW = "preview/{projectId}"
    const val PROCESSING = "processing/{projectId}"
    const val RESULT = "result/{projectId}"

    fun upload(mediaType: MediaType) = "upload/${mediaType.name}"
    fun preview(projectId: String) = "preview/$projectId"
    fun processing(projectId: String) = "processing/$projectId"
    fun result(projectId: String) = "result/$projectId"
}

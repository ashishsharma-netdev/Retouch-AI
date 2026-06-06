package com.aifacestudio.ui

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.aifacestudio.data.model.MediaType
import com.aifacestudio.ui.navigation.Routes
import com.aifacestudio.ui.screens.HistoryRoute
import com.aifacestudio.ui.screens.HomeRoute
import com.aifacestudio.ui.screens.OnboardingRoute
import com.aifacestudio.ui.screens.PreviewRoute
import com.aifacestudio.ui.screens.ProcessingRoute
import com.aifacestudio.ui.screens.ProfileRoute
import com.aifacestudio.ui.screens.ResultRoute
import com.aifacestudio.ui.screens.SplashRoute
import com.aifacestudio.ui.screens.UploadRoute

@Composable
fun StudioApp() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashRoute(navController)
        }
        composable(Routes.ONBOARDING) {
            OnboardingRoute(navController)
        }
        composable(Routes.HOME) {
            HomeRoute(navController)
        }
        composable(Routes.HISTORY) {
            HistoryRoute(navController)
        }
        composable(Routes.PROFILE) {
            ProfileRoute(navController)
        }
        composable(
            route = Routes.UPLOAD,
            arguments = listOf(navArgument("mediaType") { type = NavType.StringType })
        ) {
            UploadRoute(navController)
        }
        composable(
            route = Routes.PREVIEW,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) {
            PreviewRoute(navController)
        }
        composable(
            route = Routes.PROCESSING,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) { entry ->
            ProcessingRoute(navController, checkNotNull(entry.arguments?.getString("projectId")))
        }
        composable(
            route = Routes.RESULT,
            arguments = listOf(navArgument("projectId") { type = NavType.StringType })
        ) {
            ResultRoute(navController)
        }
    }
}

package com.saarthi.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.saarthi.feature.assistant.ui.AssistantScreen
import com.saarthi.feature.onboarding.ui.OnboardingScreen

// Type-safe route definitions
sealed class Route(val path: String) {
    data object Onboarding : Route("onboarding")
    data object Home : Route("home")
    data object Assistant : Route("assistant")
    data object MoneyMentor : Route("money_mentor")
    data object KisanSaathi : Route("kisan_saathi")
    data object Knowledge : Route("knowledge")
    data object FieldExpert : Route("field_expert")
}

@Composable
fun SaarthiNavHost(startDestination: String = Route.Onboarding.path) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Route.Onboarding.path) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Route.Home.path) {
                        popUpTo(Route.Onboarding.path) { inclusive = true }
                    }
                }
            )
        }
        composable(Route.Home.path) {
            HomeScreen(
                onNavigate = { route -> navController.navigate(route.path) }
            )
        }
        composable(Route.Assistant.path) {
            AssistantScreen(onBack = { navController.popBackStack() })
        }
        composable(Route.MoneyMentor.path) {
            MoneyMentorPlaceholder(onBack = { navController.popBackStack() })
        }
        composable(Route.KisanSaathi.path) {
            KisanSaathiPlaceholder(onBack = { navController.popBackStack() })
        }
        composable(Route.Knowledge.path) {
            KnowledgePlaceholder(onBack = { navController.popBackStack() })
        }
        composable(Route.FieldExpert.path) {
            FieldExpertPlaceholder(onBack = { navController.popBackStack() })
        }
    }
}

package com.saarthi.app.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.saarthi.app.AppStartState
import com.saarthi.app.MainViewModel
import com.saarthi.core.i18n.SupportedLanguage
import com.saarthi.core.ui.theme.SaarthiColors
import com.saarthi.feature.assistant.ui.AssistantScreen
import com.saarthi.feature.onboarding.ui.OnboardingScreen

sealed class Route(val path: String) {
    data object Onboarding   : Route("onboarding")
    data object Home         : Route("home")
    data object Assistant    : Route("assistant")
    data object MoneyMentor  : Route("money_mentor")
    data object KisanSaathi  : Route("kisan_saathi")
    data object Knowledge    : Route("knowledge")
    data object FieldExpert  : Route("field_expert")
    // Model change is onboarding re-entry — same composable, modelChange flag
    data object ModelChange  : Route("onboarding?modelChange=true")
}

@Composable
fun SaarthiNavHost(
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val startState by mainViewModel.startState.collectAsStateWithLifecycle()
    val currentLanguage by mainViewModel.currentLanguage.collectAsStateWithLifecycle()

    // Show deep-space loading screen while reading prefs / initialising engine
    if (startState == AppStartState.Loading) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SaarthiColors.DeepSpace),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = SaarthiColors.Gold)
        }
        return
    }

    // Show error if model file is missing / corrupt after onboarding was done
    if (startState is AppStartState.ModelError) {
        val msg = (startState as AppStartState.ModelError).message
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SaarthiColors.DeepSpace)
                .padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("⚠️", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(16.dp))
                Text(
                    "Model could not be loaded",
                    style = MaterialTheme.typography.titleMedium,
                    color = SaarthiColors.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = SaarthiColors.TextMuted,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = { mainViewModel.retryWithNewModel() }) {
                    Text("Re-select Model", color = SaarthiColors.Gold)
                }
            }
        }
        // If user taps "Re-select Model" we return to onboarding — handled by recomposition
        // since retryWithNewModel() sets startState = GoToOnboarding
        return
    }

    val startDest = if (startState == AppStartState.GoToHome)
        Route.Home.path else Route.Onboarding.path

    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = startDest,
    ) {
        composable(Route.Onboarding.path) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Route.Home.path) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        // Model change — same OnboardingScreen, ViewModel receives modelChange=true via SavedStateHandle
        composable(
            route = "${Route.Onboarding.path}?modelChange={modelChange}",
            arguments = listOf(navArgument("modelChange") {
                type = NavType.BoolType
                defaultValue = false
            }),
        ) {
            OnboardingScreen(
                onOnboardingComplete = {
                    navController.navigate(Route.Home.path) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Route.Home.path) {
            HomeScreen(
                onNavigate = { route -> navController.navigate(route.path) },
                onChangeModel = {
                    navController.navigate("${Route.Onboarding.path}?modelChange=true")
                },
                onChangeLanguage = { lang -> mainViewModel.setLanguage(lang) },
                currentLanguage = currentLanguage,
                greeting = currentLanguage.greeting,
                exploreSubtitle = currentLanguage.exploreSubtitle,
            )
        }

        composable(Route.Assistant.path) {
            AssistantScreen(
                onBack = { navController.popBackStack() },
                initialLanguage = currentLanguage,
                onNavigateToKnowledge = { navController.navigate(Route.Knowledge.path) }
            )
        }

        composable(Route.MoneyMentor.path) {
            MoneyMentorPlaceholder(onBack = { navController.popBackStack() })
        }

        composable(Route.KisanSaathi.path) {
            KisanSaathiPlaceholder(onBack = { navController.popBackStack() })
        }

        composable(Route.Knowledge.path) {
            com.saarthi.feature.assistant.ui.KnowledgeScreen(onBack = { navController.popBackStack() })
        }

        composable(Route.FieldExpert.path) {
            FieldExpertPlaceholder(onBack = { navController.popBackStack() })
        }
    }
}

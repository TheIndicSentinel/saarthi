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
    // Specialist-pack routes. Kisan tile bypasses its placeholder route
    // and opens AssistantScreen directly with the Kisan persona pre-
    // selected (so the curated farming pack auto-merges into RAG).
    // The placeholder route below stays as a defensive fallback for
    // anything that still navigates to it (e.g. deep links).
    data object KisanSaathi  : Route("kisan_saathi")
    /** Browseable Kisan pack landing page — destination of the Kisan tile. */
    data object KisanPack    : Route("kisan_pack")
    /** Self-contained Kisan pack chat — separate from the main assistant. */
    data object PackChat     : Route("pack_chat")
    data object Vidya        : Route("vidya")
    data object Karigar      : Route("karigar")
    data object Swasth       : Route("swasth")
    // Older route names — kept so any existing deep links don't 404.
    data object Knowledge    : Route("knowledge")
    data object FieldExpert  : Route("field_expert")
    data object Settings     : Route("settings")
    /** Saarthi Pro paywall / value screen. */
    data object Pro          : Route("pro")
    data object Privacy      : Route("privacy")
    data object About        : Route("about")
    /** Help & support — contact + problem report. */
    data object Support      : Route("support")
    data object ResponseStyle: Route("response-style")
    data object Downloads    : Route("downloads")
    // Model change is onboarding re-entry — same composable, modelChange flag
    data object ModelChange  : Route("onboarding?modelChange=true")
}

@Composable
fun SaarthiNavHost(
    mainViewModel: MainViewModel = hiltViewModel(),
) {
    val startState by mainViewModel.startState.collectAsStateWithLifecycle()
    val currentLanguage by mainViewModel.currentLanguage.collectAsStateWithLifecycle()
    val userName by mainViewModel.userName.collectAsStateWithLifecycle()

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
                    Text(currentLanguage.reselectModel, color = SaarthiColors.Gold)
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
            // Pulled in inside the composable scope so the Hilt graph for
            // PersonalityViewModel is correctly tied to this nav entry.
            val personalityVm: com.saarthi.feature.assistant.viewmodel.PersonalityViewModel = hiltViewModel()
            // Already computed by PersonalityViewModel (false only for the
            // Compact/1B model) — reused here to gate the home quick-action
            // chips instead of duplicating the tier check.
            val modelSupportsFullFeatures by personalityVm.supportedForCurrentModel.collectAsStateWithLifecycle()
            HomeScreen(
                onNavigate = { route -> navController.navigate(route.path) },
                onChangeModel = {
                    navController.navigate("${Route.Onboarding.path}?modelChange=true")
                },
                onChangeLanguage = { lang -> mainViewModel.setLanguage(lang) },
                onOpenSettings = { navController.navigate(Route.Settings.path) },
                // Kisan tile opens the Kisan-pack landing page (not
                // chat directly). The landing page is the differentiator
                // for what is, by design, a paid pack — users can see
                // the curated topics, the Govt-data sources, and the
                // suggested questions BEFORE deciding to send a message.
                // The "Open Kisan chat" CTA on that page is what does
                // the persona-select + chat navigation.
                onKisanTap = {
                    navController.navigate(Route.KisanPack.path)
                },
                onSuggestionChip = { msg ->
                    // Uri.encode uses %20 for spaces; URLEncoder uses + which
                    // NavComponent does NOT decode back to a space, giving
                    // "Translate+to+हिंदी" in the input field.
                    val encoded = android.net.Uri.encode(msg)
                    navController.navigate("${Route.Assistant.path}?msg=$encoded")
                },
                currentLanguage = currentLanguage,
                greeting = currentLanguage.greeting,
                exploreSubtitle = currentLanguage.exploreSubtitle,
                userName = userName,
                isCompactModel = !modelSupportsFullFeatures,
            )
        }

        composable(Route.Settings.path) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onNavigate = { path ->
                    when (path) {
                        "pro" -> navController.navigate(Route.Pro.path)
                        "privacy" -> navController.navigate(Route.Privacy.path)
                        "about" -> navController.navigate(Route.About.path)
                        "support" -> navController.navigate(Route.Support.path)
                        "response-style" -> navController.navigate(Route.ResponseStyle.path)
                        "downloads" -> navController.navigate(Route.Downloads.path)
                        "assistant" -> navController.navigate(Route.Assistant.path)
                    }
                },
                onChangeModel = {
                    navController.navigate("${Route.Onboarding.path}?modelChange=true")
                },
                currentLanguage = currentLanguage,
                onChangeLanguage = { lang -> mainViewModel.setLanguage(lang) },
            )
        }

        composable(Route.Pro.path) {
            PaywallScreen(onBack = { navController.popBackStack() }, language = currentLanguage)
        }

        composable(Route.Privacy.path) {
            PrivacyScreen(onBack = { navController.popBackStack() }, currentLanguage = currentLanguage)
        }
        composable(Route.About.path) {
            AboutScreen(onBack = { navController.popBackStack() }, currentLanguage = currentLanguage)
        }
        composable(Route.Support.path) {
            SupportScreen(onBack = { navController.popBackStack() }, language = currentLanguage)
        }
        composable(Route.ResponseStyle.path) {
            ResponseStyleScreen(onBack = { navController.popBackStack() }, currentLanguage = currentLanguage)
        }
        composable(Route.Downloads.path) {
            ManageDownloadsScreen(
                onBack = { navController.popBackStack() },
                onAddModel = {
                    navController.navigate("${Route.Onboarding.path}?modelChange=true")
                },
                currentLanguage = currentLanguage,
            )
        }

        composable(
            route = "${Route.Assistant.path}?msg={msg}",
            arguments = listOf(navArgument("msg") { defaultValue = "" }),
        ) { backStack ->
            val initialMessage = backStack.arguments?.getString("msg").orEmpty()
            AssistantScreen(
                onBack = { navController.popBackStack() },
                initialLanguage = currentLanguage,
                initialMessage = initialMessage,
                onNavigateToKnowledge = { navController.navigate(Route.Knowledge.path) },
                onChangeModel = {
                    navController.navigate("${Route.Onboarding.path}?modelChange=true")
                },
            )
        }

        composable(Route.KisanSaathi.path) {
            // Defensive fallback only — the Kisan tile now goes to
            // KisanPack (the live landing page) below. Anything that
            // still routes here lands on a friendly stub.
            KisanSaathiPlaceholder(onBack = { navController.popBackStack() })
        }
        composable(Route.KisanPack.path) {
            // Live pack landing page. The "Open chat" CTA opens the
            // SELF-CONTAINED pack chat (PackChatScreen) — NOT the main
            // assistant. No persona is touched, so the pack can never
            // bleed into the user's normal chat.
            com.saarthi.feature.assistant.ui.pack.KisanPackScreen(
                onBack = { navController.popBackStack() },
                onOpenKisanChat = { navController.navigate(Route.PackChat.path) },
            )
        }
        composable(Route.PackChat.path) {
            com.saarthi.feature.assistant.ui.pack.PackChatScreen(
                onBack = { navController.popBackStack() },
            )
        }
        composable(Route.Vidya.path) {
            VidyaPlaceholder(onBack = { navController.popBackStack() })
        }
        composable(Route.Karigar.path) {
            KarigarPlaceholder(onBack = { navController.popBackStack() })
        }
        composable(Route.Swasth.path) {
            SwasthPlaceholder(onBack = { navController.popBackStack() })
        }

        composable(Route.Knowledge.path) {
            com.saarthi.feature.assistant.ui.KnowledgeScreen(onBack = { navController.popBackStack() }, language = currentLanguage)
        }

        composable(Route.FieldExpert.path) {
            FieldExpertPlaceholder(onBack = { navController.popBackStack() })
        }
    }
}

@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.peerchat.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.peerchat.app.ui.theme.PeerMotion
import com.peerchat.app.ui.components.AppToastHost
import com.peerchat.app.ui.components.ErrorBoundary
import com.peerchat.app.ui.screens.ChatRouteScreen
import com.peerchat.app.ui.screens.DocumentsScreen
import com.peerchat.app.ui.screens.HomeScreen
import com.peerchat.app.ui.screens.ModelsScreen
import com.peerchat.app.ui.theme.PeerChatTheme

private const val ROUTE_HOME = "home"
private const val ROUTE_CHAT = "chat/{chatId}"
private const val ROUTE_DOCUMENTS = "documents"
private const val ROUTE_MODELS = "models"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_REASONING = "reasoning/{chatId}"

@Composable
fun PeerChatRoot() {
    PeerChatTheme(darkTheme = true) {
        ErrorBoundary {
            val navController = rememberNavController()

            Scaffold(
                snackbarHost = { AppToastHost() }
            ) { paddingValues ->
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = androidx.compose.ui.Modifier.padding(paddingValues)
                ) {
                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_HOME,
                        enterTransition = { PeerMotion.sharedAxisXIn(true) },
                        exitTransition = { PeerMotion.sharedAxisXOut(true) },
                        popEnterTransition = { PeerMotion.sharedAxisXIn(false) },
                        popExitTransition = { PeerMotion.sharedAxisXOut(false) }
                    ) {
                        composable(ROUTE_HOME) {
                            HomeScreen(navController = navController)
                        }
                        composable(ROUTE_CHAT) { backStackEntry ->
                            val chatIdArg = backStackEntry.arguments?.getString("chatId")?.toLongOrNull()
                            if (chatIdArg != null) {
                                ChatRouteScreen(navController = navController, chatId = chatIdArg)
                            } else {
                                Text("Invalid chat")
                            }
                        }
                        composable(ROUTE_DOCUMENTS) {
                            DocumentsScreen(onBack = { navController.popBackStack() })
                        }
                        composable(ROUTE_MODELS) {
                            ModelsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}


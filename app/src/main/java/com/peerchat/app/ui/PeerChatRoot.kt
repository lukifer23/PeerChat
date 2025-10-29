@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.peerchat.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
        val navController = rememberNavController()
        Surface(color = MaterialTheme.colorScheme.background) {
            NavHost(navController = navController, startDestination = ROUTE_HOME) {
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

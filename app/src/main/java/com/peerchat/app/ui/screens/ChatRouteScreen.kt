package com.peerchat.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.peerchat.app.ui.HomeViewModel

import com.peerchat.app.ui.ChatViewModel
import androidx.lifecycle.ViewModel
import android.app.Application

@Composable
fun ChatRouteScreen(navController: NavHostController, chatId: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val application = context.applicationContext as android.app.Application
    val viewModel: ChatViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(application, chatId) as T
            }
        }
    )
    val uiState by viewModel.uiState.collectAsState()

    ChatScreen(
        messages = uiState.messages,
        onSend = { prompt, onToken, onComplete ->
            viewModel.sendPrompt(prompt, onToken, onComplete)
        },
        onBack = { navController.popBackStack() }
    )
}

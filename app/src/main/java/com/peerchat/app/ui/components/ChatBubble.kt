package com.peerchat.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class BubbleRole { User, Assistant }

@Composable
fun ChatBubble(
    role: BubbleRole,
    content: @Composable () -> Unit
) {
    val isUser = role == BubbleRole.User
    val bg = if (isUser) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface
    val onBg = MaterialTheme.colorScheme.onSurface
    val align = if (isUser) Alignment.End else Alignment.Start
    val shape = if (isUser)
        RoundedCornerShape(topStart = 16.dp, topEnd = 4.dp, bottomStart = 16.dp, bottomEnd = 16.dp)
    else
        RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalAlignment = align
    ) {
        Surface(
            color = bg,
            contentColor = onBg,
            tonalElevation = if (isUser) 2.dp else 1.dp,
            shadowElevation = 0.dp,
            shape = shape
        ) {
            Column(
                modifier = Modifier
                    .clip(shape)
                    .background(bg)
                    .padding(PaddingValues(14.dp))
            ) {
                content()
            }
        }
    }
}

@Composable
fun ChatBubbleText(
    role: BubbleRole,
    text: String
) {
    ChatBubble(role = role) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = Int.MAX_VALUE,
            overflow = TextOverflow.Visible
        )
    }
}



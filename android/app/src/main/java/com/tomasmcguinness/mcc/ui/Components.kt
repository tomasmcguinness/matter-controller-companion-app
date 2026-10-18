package com.tomasmcguinness.mcc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** The Android counterpart of SwiftUI's `ContentUnavailableView`. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    action: Pair<String, () -> Unit>? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (action != null) {
            Spacer(Modifier.height(16.dp))
            Button(onClick = action.second) { Text(action.first) }
        }
    }
}

class SwipeAction(val label: String, val icon: ImageVector, val color: Color, val onTrigger: () -> Unit)

/**
 * A row with swipe actions, like SwiftUI's `swipeActions`. The row always springs back; the
 * action decides what happens next (a confirmation, a navigation, an optimistic removal).
 */
@Composable
fun SwipeActionsRow(
    start: SwipeAction? = null,
    end: SwipeAction? = null,
    content: @Composable () -> Unit,
) {
    val currentStart by rememberUpdatedState(start)
    val currentEnd by rememberUpdatedState(end)

    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> currentStart?.onTrigger?.invoke()
                SwipeToDismissBoxValue.EndToStart -> currentEnd?.onTrigger?.invoke()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        },
    )

    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = start != null,
        enableDismissFromEndToStart = end != null,
        backgroundContent = {
            val fromStart = state.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            val action = when (state.dismissDirection) {
                SwipeToDismissBoxValue.StartToEnd -> start
                SwipeToDismissBoxValue.EndToStart -> end
                SwipeToDismissBoxValue.Settled -> null
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(action?.color ?: Color.Transparent)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (fromStart) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                if (action != null) {
                    Icon(action.icon, contentDescription = action.label, tint = Color.White)
                }
            }
        },
    ) {
        Surface { content() }
    }
}

/** The "Something Went Wrong" alert the iOS screens share. */
@Composable
fun ErrorDialog(message: String?, onDismiss: () -> Unit, title: String = "Something Went Wrong") {
    if (message == null) return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
        title = { Text(title) },
        text = { Text(message) },
    )
}

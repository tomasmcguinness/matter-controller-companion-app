package com.tomasmcguinness.mcc.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.ui.EmptyState
import com.tomasmcguinness.mcc.ui.SwipeAction
import com.tomasmcguinness.mcc.ui.SwipeActionsRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpen: (Controller) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Controller) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val controllers by viewModel.controllers.collectAsStateWithLifecycle()
    var pendingDeletion by remember { mutableStateOf<Controller?>(null) }

    // Runs again whenever Home comes back on screen, e.g. after adding or editing a controller.
    LaunchedEffect(Unit) { viewModel.load() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home") },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Controller")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        if (controllers.isEmpty()) {
            EmptyState(
                icon = Icons.Filled.Home,
                title = "No Controllers",
                description = "Tap + to add a Matter controller by name and address.",
                modifier = Modifier.padding(padding),
                action = "Add Controller" to onAdd,
            )
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(controllers, key = { it.id }) { controller ->
                    SwipeActionsRow(
                        start = SwipeAction("Edit", Icons.Filled.Edit, MaterialTheme.colorScheme.primary) { onEdit(controller) },
                        end = SwipeAction("Delete", Icons.Filled.Delete, MaterialTheme.colorScheme.error) { pendingDeletion = controller },
                    ) {
                        ControllerRow(
                            controller = controller,
                            onClick = { onOpen(controller) },
                            onLongClick = { onEdit(controller) },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    pendingDeletion?.let { controller ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text("Delete \"${controller.name}\"?") },
            text = { Text("This removes the controller from this app. Devices on the controller are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(controller)
                    pendingDeletion = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeletion = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ControllerRow(controller: Controller, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(controller.name) },
        supportingContent = { Text(controller.url) },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Edit"),
    )
}

package com.tomasmcguinness.mcc.devices

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.ui.EmptyState
import com.tomasmcguinness.mcc.ui.ErrorDialog
import com.tomasmcguinness.mcc.ui.SwipeAction
import com.tomasmcguinness.mcc.ui.SwipeActionsRow

@Composable
fun DeviceListScreen(controllerId: String, onBack: () -> Unit) {
    val controller = remember(controllerId) { ControllerStore.controller(controllerId) }

    if (controller == null) {
        // Deleted out from under us; there's nothing to show.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    DeviceList(controller, onBack, viewModel(key = controller.id) { DeviceListViewModel(controller) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceList(controller: Controller, onBack: () -> Unit, viewModel: DeviceListViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val setupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.commissioningFinished(result.resultCode, result.data)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(controller.name) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.startCommissioning(context) { sender ->
                                setupLauncher.launch(IntentSenderRequest.Builder(sender).build())
                            }
                        },
                        enabled = !state.isWaitingForCommissioning,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Device")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                if (state.isWaitingForCommissioning) {
                    item {
                        ListItem(
                            leadingContent = { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) },
                            headlineContent = {
                                Text("Commissioning a new device…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            },
                        )
                        HorizontalDivider()
                    }
                }

                items(state.nodes, key = { it.nodeId.toString() }) { node ->
                    SwipeActionsRow(
                        end = SwipeAction("Remove", Icons.Filled.Delete, MaterialTheme.colorScheme.error) { viewModel.unpair(node) },
                    ) {
                        ListItem(headlineContent = { Text(node.displayName) })
                    }
                    HorizontalDivider()
                }

                if (state.nodes.isNotEmpty()) {
                    item {
                        val count = state.nodes.size
                        Text(
                            "$count device${if (count == 1) "" else "s"} on ${controller.name}.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else if (!state.isLoading && !state.isWaitingForCommissioning) {
                    // Inside the list so the empty state can still be pulled to refresh.
                    item {
                        EmptyState(
                            icon = Icons.Filled.Search,
                            title = "No Devices",
                            description = "Tap + to commission a Matter device onto ${controller.name}.",
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else if (state.isLoading && !state.isRefreshing) {
                    item {
                        Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
            }
        }
    }

    ErrorDialog(message = state.errorMessage, onDismiss = viewModel::dismissError)
}

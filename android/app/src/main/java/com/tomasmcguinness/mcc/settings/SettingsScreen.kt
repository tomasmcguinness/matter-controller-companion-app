package com.tomasmcguinness.mcc.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tomasmcguinness.mcc.BuildConfig
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.notifications.PushManager
import com.tomasmcguinness.mcc.notifications.PushManager.DeliveryStatus
import com.tomasmcguinness.mcc.ui.ErrorDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val push by PushManager.state.collectAsStateWithLifecycle()
    var controllers by remember { mutableStateOf(emptyList<Controller>()) }
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        PushManager.enable(granted)
    }

    LaunchedEffect(Unit) {
        controllers = ControllerStore.controllers
        PushManager.distributeWhereUnknown()
    }

    // Picks up a change made in system settings when the user comes back.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        PushManager.refreshAuthorizationStatus()
    }

    fun setEnabled(enabled: Boolean) {
        when {
            !enabled -> PushManager.disable()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else -> PushManager.enable(true)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Settings") }) },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader("Notifications")

            ListItem(
                headlineContent = { Text("Notifications") },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (push.isWorking) {
                            CircularProgressIndicator(Modifier.padding(end = 12.dp).size(20.dp), strokeWidth = 2.dp)
                        }
                        Switch(checked = push.isEnabled, onCheckedChange = ::setEnabled, enabled = !push.isWorking)
                    }
                },
            )

            if (push.isEnabled) {
                for (controller in controllers) {
                    NotificationControllerRow(
                        controller = controller,
                        isSelected = controller.id in push.selectedControllerIds,
                        hasCredentials = PushManager.hasCredentials(controller),
                        status = push.statuses[controller.id],
                        isTesting = controller.id in push.testing,
                        onToggle = { PushManager.toggle(controller) },
                        onTest = { PushManager.sendTest(controller) },
                    )
                }
            }

            when {
                push.notificationsBlocked -> Column {
                    SectionFooter("Notifications are turned off for MCC.")
                    TextButton(modifier = Modifier.padding(horizontal = 4.dp), onClick = {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                        )
                    }) {
                        Text("Open Settings")
                    }
                }
                push.isEnabled -> SectionFooter(
                    "Tap a controller to let it send you notifications, and tap it again to stop. Each one needs a push relay username and password, which you set when you edit it.",
                )
                else -> SectionFooter("Lets your controllers send you notifications.")
            }

            SectionHeader("About")
            ListItem(headlineContent = { Text("App") }, trailingContent = { Text("The Matter Controller Companion App") })
            ListItem(headlineContent = { Text("Version") }, trailingContent = { Text(BuildConfig.VERSION_NAME) })
            ListItem(headlineContent = { Text("Build") }, trailingContent = { Text(BuildConfig.VERSION_CODE.toString()) })
        }
    }

    ErrorDialog(message = push.errorMessage, onDismiss = PushManager::dismissError)
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SectionFooter(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** One controller in the notifications list. Tapping the row ticks or unticks it. */
@Composable
private fun NotificationControllerRow(
    controller: Controller,
    isSelected: Boolean,
    hasCredentials: Boolean,
    status: DeliveryStatus?,
    isTesting: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit,
) {
    val detail = when {
        !hasCredentials -> "Needs a push relay username and password"
        status == DeliveryStatus.NotSupported -> "This controller doesn't support notifications"
        status is DeliveryStatus.Failed -> status.message
        else -> null
    }

    val detailIsProblem = status == DeliveryStatus.NotSupported || status is DeliveryStatus.Failed

    val supporting: (@Composable () -> Unit)? = detail?.let { text ->
        @Composable {
            Text(text, color = if (detailIsProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    ListItem(
        headlineContent = { Text(controller.name) },
        supportingContent = supporting,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status == DeliveryStatus.Sending || isTesting) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (isSelected) {
                    // A button of its own, so it takes its own taps instead of toggling the row.
                    TextButton(onClick = onTest) { Text("Send Test") }
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "Sending notifications",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        },
        modifier = Modifier.clickable(role = Role.Checkbox, onClick = onToggle),
    )
}

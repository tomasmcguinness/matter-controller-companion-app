package com.tomasmcguinness.mcc.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.tomasmcguinness.mcc.data.ClientError
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.data.MccClient
import com.tomasmcguinness.mcc.data.RelayCredentialStore
import com.tomasmcguinness.mcc.notifications.PushManager
import com.tomasmcguinness.mcc.ui.ErrorDialog
import kotlinx.coroutines.launch

/** Adds a controller, or edits the one with [editingId]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddControllerScreen(editingId: String?, onDone: () -> Unit) {
    val editing = remember(editingId) { editingId?.let(ControllerStore::controller) }

    // The password this controller had when the screen opened, to tell whether it changed.
    val originalPassword = remember(editing) { editing?.let { RelayCredentialStore.password(it.id) }.orEmpty() }

    var name by rememberSaveable { mutableStateOf(editing?.name.orEmpty()) }
    var addressText by rememberSaveable { mutableStateOf(editing?.url.orEmpty()) }
    var relayUsername by rememberSaveable { mutableStateOf(editing?.relayUsername.orEmpty()) }
    var relayPassword by rememberSaveable { mutableStateOf(originalPassword) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val isEditing = editing != null

    val trimmedName = name.trim()

    // Null when blank, so a controller without notifications has no username at all.
    val trimmedRelayUsername = relayUsername.trim().ifEmpty { null }

    val url = Controller.connectionUrl(addressText)

    val canSave = when {
        trimmedName.isEmpty() || url == null -> false
        editing != null -> trimmedName != editing.name ||
            url != editing.url ||
            trimmedRelayUsername != editing.relayUsername ||
            relayPassword != originalPassword
        else -> true
    }

    fun save() {
        if (url == null) {
            errorMessage = "Enter a valid http or https address, e.g. http://192.168.1.250."
            return
        }

        val candidate = Controller(
            id = editing?.id ?: java.util.UUID.randomUUID().toString(),
            name = trimmedName,
            url = url,
            relayUsername = trimmedRelayUsername,
        )

        scope.launch {
            isVerifying = true

            try {
                // A rename alone shouldn't fail just because the hub is offline right now.
                if (url != editing?.url) {
                    MccClient(candidate).checkReachable()
                }

                if (isEditing) {
                    ControllerStore.update(candidate)
                } else {
                    ControllerStore.add(candidate)
                }

                RelayCredentialStore.setPassword(if (trimmedRelayUsername == null) null else relayPassword, candidate.id)

                // Don't hold the screen open while the controller is told; Settings shows how it went.
                PushManager.controllerSaved(candidate)

                onDone()
            } catch (e: ClientError) {
                errorMessage = e.message
            } finally {
                isVerifying = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Controller" else "Add Controller") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "Cancel")
                    }
                },
                actions = {
                    TextButton(onClick = ::save, enabled = canSave && !isVerifying) {
                        Text(if (isVerifying) "Checking…" else if (isEditing) "Save" else "Add")
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("e.g. Heating Monitor") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = addressText,
                onValueChange = { addressText = it },
                label = { Text("Address") },
                placeholder = { Text("http://192.168.1.250") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "Push Relay",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 12.dp),
            )

            OutlinedTextField(
                value = relayUsername,
                onValueChange = { relayUsername = it },
                label = { Text("Username") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = relayPassword,
                onValueChange = { relayPassword = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "The controller signs in to the push relay with these to send you notifications. Leave them blank if this controller shouldn't send notifications.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    ErrorDialog(
        message = errorMessage,
        onDismiss = { errorMessage = null },
        title = if (isEditing) "Couldn't Save Controller" else "Couldn't Add Controller",
    )
}

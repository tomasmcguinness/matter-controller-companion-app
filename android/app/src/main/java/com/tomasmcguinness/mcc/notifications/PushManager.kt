package com.tomasmcguinness.mcc.notifications

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.tomasmcguinness.mcc.data.ClientError
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.data.MccClient
import com.tomasmcguinness.mcc.data.NotificationTarget
import com.tomasmcguinness.mcc.data.PushRequest
import com.tomasmcguinness.mcc.data.RelayCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Owns this phone's FCM registration and hands it to the controllers the user picked.
 *
 * Controllers hold the phone's FCM token, plus their own relay username and password, and post
 * to the push relay themselves. Turning notifications on only gets a token; the user then ticks
 * each controller that should notify them, which sends that controller the details. Unticking
 * tells it to forget them.
 *
 * The public functions start their work in the manager's own scope, so leaving a screen doesn't
 * cancel a controller update midway.
 */
object PushManager {

    sealed interface DeliveryStatus {
        data object Sending : DeliveryStatus
        data object Sent : DeliveryStatus
        data object NotSupported : DeliveryStatus
        data class Failed(val message: String) : DeliveryStatus
    }

    data class State(
        val isEnabled: Boolean = false,
        /** The controllers the user ticked -- the ones that have been sent this phone's details. */
        val selectedControllerIds: Set<String> = emptySet(),
        val isWorking: Boolean = false,
        /** The user turned MCC's notifications off in system settings, or refused the permission. */
        val notificationsBlocked: Boolean = false,
        /** What happened the last time this phone's details were sent to each controller. */
        val statuses: Map<String, DeliveryStatus> = emptyMap(),
        /** Controllers with a test notification in flight. */
        val testing: Set<String> = emptySet(),
        val errorMessage: String? = null,
    )

    private const val TAG = "PushManager"

    private const val IS_ENABLED_KEY = "pushNotificationsEnabled"
    private const val DEVICE_TOKEN_KEY = "pushDeviceToken"
    private const val SELECTED_CONTROLLER_IDS_KEY = "pushSelectedControllerIds"
    private const val PERMISSION_DENIED_KEY = "pushPermissionDenied"

    private val scope = MainScope()

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val current get() = _state.value

    private var deviceToken: String?
        get() = prefs.getString(DEVICE_TOKEN_KEY, null)
        set(value) = prefs.edit { putString(DEVICE_TOKEN_KEY, value) }

    fun init(context: Context) {
        this.context = context.applicationContext
        prefs = context.getSharedPreferences("push", Context.MODE_PRIVATE)

        _state.value = State(
            isEnabled = prefs.getBoolean(IS_ENABLED_KEY, false),
            selectedControllerIds = prefs.getStringSet(SELECTED_CONTROLLER_IDS_KEY, null).orEmpty().toSet(),
        )
    }

    /** Updates the state, saving the parts that outlive the process. */
    private fun update(transform: (State) -> State) {
        _state.update(transform)

        prefs.edit {
            putBoolean(IS_ENABLED_KEY, current.isEnabled)
            putStringSet(SELECTED_CONTROLLER_IDS_KEY, current.selectedControllerIds)
        }
    }

    fun dismissError() = update { it.copy(errorMessage = null) }

    // region Lifecycle

    /**
     * Called at launch. The token can change while the app isn't running (restores, data
     * cleared), and [MccMessagingService.onNewToken] isn't guaranteed to have seen it, so check.
     */
    fun handleLaunch() {
        refreshAuthorizationStatus()

        if (!current.isEnabled || !isFirebaseConfigured()) return

        scope.launch {
            try {
                didRegister(FirebaseMessaging.getInstance().token.await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't fetch the FCM token", e)
            }
        }
    }

    fun refreshAuthorizationStatus() {
        val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()

        // Before Android 13 notifications are on unless the user turned them off. From 13 they
        // start off until we ask, so only call them blocked once the user has said no.
        val asked = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.getBoolean(PERMISSION_DENIED_KEY, false)

        if (enabled) {
            prefs.edit { remove(PERMISSION_DENIED_KEY) }
        }

        update { it.copy(notificationsBlocked = !enabled && asked) }
    }

    /** Called with the current FCM token, from launch or [MccMessagingService.onNewToken]. */
    fun didRegister(token: String) {
        scope.launch {
            // Controllers only need telling if the token moved.
            if (!current.isEnabled || token == deviceToken) return@launch

            Log.i(TAG, "FCM token changed; updating controllers")

            deviceToken = token

            distributeToSelectedControllers()
        }
    }

    // endregion

    // region Turning it on and off

    /** [permissionGranted] is the answer to the POST_NOTIFICATIONS request, or true where there isn't one. */
    fun enable(permissionGranted: Boolean) {
        scope.launch {
            update { it.copy(isWorking = true) }

            try {
                if (!permissionGranted || !NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    prefs.edit { putBoolean(PERMISSION_DENIED_KEY, true) }
                    refreshAuthorizationStatus()
                    update { it.copy(errorMessage = "Notifications are turned off for MCC. Turn them on in Settings, then try again.") }
                    return@launch
                }

                refreshAuthorizationStatus()

                if (!isFirebaseConfigured()) {
                    update { it.copy(errorMessage = "This build of MCC isn't set up for notifications. It needs the Firebase project's google-services.json.") }
                    return@launch
                }

                deviceToken = FirebaseMessaging.getInstance().token.await()
                update { it.copy(isEnabled = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                update { it.copy(errorMessage = "Couldn't register for notifications. ${e.message.orEmpty()}") }
            } finally {
                update { it.copy(isWorking = false) }
            }
        }
    }

    fun disable() {
        scope.launch {
            update { it.copy(isWorking = true) }

            // Best effort: a controller that's offline has nothing we can do about it now, and
            // there's no way to retry later once we've let go of it.
            coroutineScope {
                for (controller in selectedControllers()) {
                    launch {
                        try {
                            MccClient(controller).clearNotificationTarget()
                        } catch (e: ClientError) {
                            Log.w(TAG, "Couldn't tell ${controller.name} to stop", e)
                        }
                    }
                }
            }

            deviceToken = null
            update {
                it.copy(isEnabled = false, selectedControllerIds = emptySet(), statuses = emptyMap(), isWorking = false)
            }
        }
    }

    private fun isFirebaseConfigured() = FirebaseApp.getApps(context).isNotEmpty()

    // endregion

    // region Choosing controllers

    fun hasCredentials(controller: Controller) = credentials(controller) != null

    /** Ticks or unticks a controller: sends it this phone's details, or tells it to forget them. */
    fun toggle(controller: Controller) {
        scope.launch {
            if (!current.isEnabled || current.statuses[controller.id] == DeliveryStatus.Sending) return@launch

            if (controller.id in current.selectedControllerIds) {
                deselect(controller)
            } else {
                select(controller)
            }
        }
    }

    private suspend fun select(controller: Controller) {
        if (!hasCredentials(controller)) {
            update {
                it.copy(errorMessage = "Add a push relay username and password to ${controller.name} first. Swipe right on it in Home to edit it.")
            }
            return
        }

        distribute(controller)

        if (current.statuses[controller.id] == DeliveryStatus.Sent) {
            update { it.copy(selectedControllerIds = it.selectedControllerIds + controller.id) }
        }
    }

    private suspend fun deselect(controller: Controller) {
        setStatus(controller.id, DeliveryStatus.Sending)

        // Untick whatever happens -- otherwise a controller that's gone for good could never be
        // removed -- but say so if it may still be sending.
        try {
            MccClient(controller).clearNotificationTarget()
        } catch (e: ClientError.HttpError) {
            // A 404 means it never supported notifications, so there's nothing to stop.
            if (e.status != 404) {
                update { it.copy(errorMessage = "${controller.name} may keep sending notifications until it can be reached. ${e.message}") }
            }
        } catch (e: ClientError) {
            update { it.copy(errorMessage = "${controller.name} may keep sending notifications until it can be reached. ${e.message}") }
        }

        update { it.copy(selectedControllerIds = it.selectedControllerIds - controller.id) }
        setStatus(controller.id, null)
    }

    // endregion

    // region Sending details to controllers

    private fun selectedControllers() =
        ControllerStore.controllers.filter { it.id in current.selectedControllerIds }

    private suspend fun distributeToSelectedControllers() = coroutineScope {
        for (controller in selectedControllers()) {
            launch { distribute(controller) }
        }
    }

    /**
     * Re-checks ticked controllers with no status yet, e.g. after a relaunch, without re-sending
     * to the ones already known to be set up.
     */
    fun distributeWhereUnknown() {
        scope.launch {
            coroutineScope {
                for (controller in selectedControllers().filter { current.statuses[it.id] == null }) {
                    launch { distribute(controller) }
                }
            }
        }
    }

    /** Sends this phone's token and the controller's relay credentials to the controller. */
    private suspend fun distribute(controller: Controller) {
        val token = deviceToken
        val credentials = credentials(controller)

        if (!current.isEnabled || token == null || credentials == null) return

        setStatus(controller.id, DeliveryStatus.Sending)

        val target = NotificationTarget(
            relayUrl = PushRelay.baseUrl,
            username = credentials.first,
            password = credentials.second,
            deviceToken = token,
            environment = PushRelay.ENVIRONMENT,
        )

        val status = try {
            MccClient(controller).setNotificationTarget(target)
            DeliveryStatus.Sent
        } catch (e: ClientError.HttpError) {
            if (e.status == 404) DeliveryStatus.NotSupported else DeliveryStatus.Failed(e.message.orEmpty())
        } catch (e: ClientError) {
            DeliveryStatus.Failed(e.message.orEmpty())
        }

        setStatus(controller.id, status)
    }

    /**
     * Called after a controller is edited, so a ticked one gets its new credentials or address
     * -- or is unticked when its credentials were removed.
     */
    fun controllerSaved(controller: Controller) {
        scope.launch {
            if (!current.isEnabled || controller.id !in current.selectedControllerIds) return@launch

            if (hasCredentials(controller)) {
                distribute(controller)
            } else {
                deselect(controller)
            }
        }
    }

    fun controllerRemoved(id: String) {
        update { it.copy(selectedControllerIds = it.selectedControllerIds - id, statuses = it.statuses - id) }
    }

    private fun setStatus(id: String, status: DeliveryStatus?) {
        update { it.copy(statuses = if (status == null) it.statuses - id else it.statuses + (id to status)) }
    }

    // endregion

    // region Testing

    /**
     * Sends a notification through the relay using this controller's credentials, the same way
     * the controller would, so it checks the relay and the credentials together.
     */
    fun sendTest(controller: Controller) {
        val token = deviceToken ?: return
        val credentials = credentials(controller) ?: return

        scope.launch {
            update { it.copy(testing = it.testing + controller.id) }

            val push = PushRequest(
                deviceToken = token,
                environment = PushRelay.ENVIRONMENT,
                title = controller.name,
                body = "This is a test notification from MCC.",
            )

            try {
                PushRelay.send(push, credentials.first, credentials.second)
            } catch (e: PushRelayError) {
                update { it.copy(errorMessage = e.message) }
            } finally {
                update { it.copy(testing = it.testing - controller.id) }
            }
        }
    }

    private fun credentials(controller: Controller): Pair<String, String>? {
        val username = controller.relayUsername?.takeIf { it.isNotEmpty() } ?: return null
        val password = RelayCredentialStore.password(controller.id)?.takeIf { it.isNotEmpty() } ?: return null

        return username to password
    }

    // endregion
}

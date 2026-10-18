package com.tomasmcguinness.mcc.devices

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.home.matter.Matter
import com.google.android.gms.home.matter.commissioning.CommissioningRequest
import com.google.android.gms.home.matter.commissioning.CommissioningResult
import com.tomasmcguinness.mcc.commissioning.MccCommissioningService
import com.tomasmcguinness.mcc.data.ClientError
import com.tomasmcguinness.mcc.data.Controller
import com.tomasmcguinness.mcc.data.ControllerStore
import com.tomasmcguinness.mcc.data.MccClient
import com.tomasmcguinness.mcc.data.Node
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class DeviceListViewModel(val controller: Controller) : ViewModel() {

    data class State(
        val nodes: List<Node> = emptyList(),
        val isLoading: Boolean = false,
        /** A pull to refresh is in progress, as opposed to a load the screen started itself. */
        val isRefreshing: Boolean = false,
        val isWaitingForCommissioning: Boolean = false,
        val errorMessage: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val client = MccClient(controller)

    init {
        viewModelScope.launch { load() }
    }

    fun dismissError() = _state.update { it.copy(errorMessage = null) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isRefreshing = true) }
            load()
            _state.update { it.copy(isRefreshing = false) }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(isLoading = true) }

        try {
            val nodes = client.nodes().sortedBy { it.nodeId }
            _state.update { it.copy(nodes = nodes) }
        } catch (e: ClientError) {
            _state.update { it.copy(errorMessage = e.message) }
        } finally {
            _state.update { it.copy(isLoading = false) }
        }
    }

    fun unpair(node: Node) {
        // Drop it from the list straight away; the reload below puts it back if the controller
        // disagreed.
        _state.update { state -> state.copy(nodes = state.nodes.filterNot { it.nodeId == node.nodeId }) }

        viewModelScope.launch {
            try {
                client.unpair(node.nodeId)
            } catch (e: ClientError) {
                _state.update { it.copy(errorMessage = e.message) }
            }

            load()
        }
    }

    // region Commissioning

    /**
     * Hands off to the Google Play Services Matter setup flow.
     *
     * Everything from here -- camera, QR scan, manual pairing code entry, Wi-Fi and Thread
     * provisioning -- is Google's UI. Once the device is on the network, Play Services binds to
     * [MccCommissioningService], which is where the call to this specific controller happens.
     * We record which controller this attempt is for beforehand, since the service has no other
     * way to know.
     */
    fun startCommissioning(context: Context, launch: (IntentSender) -> Unit) {
        ControllerStore.pendingCommissioningControllerId = controller.id

        val request = CommissioningRequest.builder()
            .setCommissioningService(ComponentName(context, MccCommissioningService::class.java))
            .build()

        viewModelScope.launch {
            try {
                launch(Matter.getCommissioningClient(context).commissionDevice(request).await())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ControllerStore.pendingCommissioningControllerId = null
                _state.update { it.copy(errorMessage = "Couldn't start device setup. ${e.message.orEmpty()}") }
            }
        }
    }

    /** Called with what the Play Services setup flow returned. */
    fun commissioningFinished(resultCode: Int, data: Intent?) {
        if (resultCode != Activity.RESULT_OK) {
            // The user cancelled, or setup failed -- Play Services has already said why.
            ControllerStore.pendingCommissioningControllerId = null
            return
        }

        val result = CommissioningResult.fromIntentSenderResult(resultCode, data)

        // The token is the node id our commissioning service handed back.
        val nodeId = result.token?.toULongOrNull()
        val name = result.deviceName

        viewModelScope.launch {
            // Push the name the user typed during setup to the controller, so the device shows
            // up with the same name in the app.
            if (nodeId != null && !name.isNullOrBlank()) {
                try {
                    client.rename(nodeId, name)
                } catch (e: ClientError) {
                    // Naming is cosmetic -- the device is already commissioned.
                    Log.e(TAG, "Couldn't rename node $nodeId", e)
                }
            }

            awaitCommissionedNode(nodeId)
        }
    }

    /**
     * Waits for a freshly commissioned node to show up.
     *
     * The controller doesn't answer `POST /api/companion/nodes` until commissioning has
     * finished, so the node is normally there on the first refresh; the retries only cover the
     * case where the service's view of the outcome and the node list disagree.
     */
    private suspend fun awaitCommissionedNode(expectedNodeId: ULong?) {
        _state.update { it.copy(isWaitingForCommissioning = true) }

        try {
            repeat(20) { attempt ->
                if (attempt > 0) delay(3_000)

                load()

                // No id to wait for -- one refresh is all we can usefully do.
                if (expectedNodeId == null) return

                if (_state.value.nodes.any { it.nodeId == expectedNodeId }) return
            }

            _state.update {
                it.copy(errorMessage = "The device didn't finish commissioning. Check the controller's logs, then pull to refresh.")
            }
        } finally {
            _state.update { it.copy(isWaitingForCommissioning = false) }
            ControllerStore.pendingCommissioningControllerId = null
        }
    }

    // endregion

    private companion object {
        const val TAG = "DeviceListViewModel"
    }
}

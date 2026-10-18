package com.tomasmcguinness.mcc.commissioning

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.google.android.gms.home.matter.commissioning.CommissioningCompleteMetadata
import com.google.android.gms.home.matter.commissioning.CommissioningError
import com.google.android.gms.home.matter.commissioning.CommissioningRequestMetadata
import com.google.android.gms.home.matter.commissioning.CommissioningService
import com.tomasmcguinness.mcc.data.MccClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Handles the commissioning half of the Google Play Services Matter setup flow.
 *
 * Play Services binds to this once the user has scanned a device's code and Play Services has
 * put the device on Wi-Fi or Thread and opened a commissioning window on it. We are not the
 * Matter controller -- whichever controller the user picked from Home is. All this does is tell
 * that controller where the device is and its passcode, and the controller pairs it onto its
 * fabric. That call blocks until commissioning finishes, so what it answers with is the real
 * outcome.
 *
 * Nothing in the request says which controller it's for -- the app records that in
 * [com.tomasmcguinness.mcc.data.ControllerStore.pendingCommissioningControllerId] right before
 * it starts the flow, and [MccClient.forPendingCommissioningTarget] reads it back here.
 */
class MccCommissioningService : Service(), CommissioningService.Callback {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var delegate: CommissioningService

    override fun onCreate() {
        super.onCreate()
        delegate = CommissioningService.Builder(this).setCallback(this).build()
    }

    override fun onBind(intent: Intent): IBinder = delegate.asBinder()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCommissioningRequested(metadata: CommissioningRequestMetadata) {
        scope.launch {
            try {
                val client = MccClient.forPendingCommissioningTarget()
                val location = metadata.networkLocation

                // An IPv6 scope (fe80::1%wlan0) only means something on this phone.
                val address = location.ipAddress.hostAddress.orEmpty().substringBefore('%')

                Log.i(TAG, "Commissioning the device at [$address]:${location.port}")

                // Blocks for as long as commissioning takes -- up to about 90 seconds.
                val nodeId = client.commissionOnNetwork(address, location.port, metadata.passcode)

                Log.i(TAG, "Controller commissioned node $nodeId")

                // The token comes back to the app in the CommissioningResult, so it knows
                // which node to wait for.
                delegate.sendCommissioningComplete(
                    CommissioningCompleteMetadata.builder().setToken(nodeId.toString()).build(),
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Report it so Play Services shows a failure rather than claiming the device
                // was added.
                Log.e(TAG, "Commissioning failed", e)
                delegate.sendCommissioningError(CommissioningError.OTHER)
            }
        }
    }

    private companion object {
        const val TAG = "Commissioning"
    }
}

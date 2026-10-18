package com.tomasmcguinness.mcc.data

import android.util.Log
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Talks to a Matter controller's REST API.
 *
 * Holds no state beyond the controller it was built for. There is no authentication in this
 * API -- every request is plain, unauthenticated JSON over HTTP on the local network.
 */
class MccClient(
    val controller: Controller,
    private val http: OkHttpClient = sharedHttpClient,
) {
    companion object {
        private const val TAG = "MccClient"

        private val jsonMediaType = "application/json".toMediaType()

        /**
         * Builds a client for whatever controller a commissioning attempt is currently
         * targeting, or throws if there isn't one. Used from the commissioning service, which
         * has no controller of its own in scope.
         */
        fun forPendingCommissioningTarget(): MccClient {
            val controller = ControllerStore.pendingCommissioningControllerId
                ?.let(ControllerStore::controller)
                ?: throw ClientError.NoPendingCommissioningTarget()

            return MccClient(controller)
        }
    }

    // region Endpoints

    /**
     * Confirms the controller is reachable. Used right after the user types in an address, so a
     * bad entry fails immediately rather than on the device list.
     */
    suspend fun checkReachable() {
        perform("/api/companion/info", "GET", null, 20)
    }

    suspend fun nodes(): List<Node> = decode(perform("/api/companion/nodes", "GET", null, 20))

    /**
     * Asks the controller to commission a device that's already on the network with an open
     * commissioning window, which is how Google Play Services hands devices over.
     *
     * The controller holds the request open until commissioning finishes, so this takes as long
     * as pairing does and a success means the device really did join the fabric. Anything else
     * throws, including the controller's own 504 when it gave up waiting.
     */
    suspend fun commissionOnNetwork(ipAddress: String, port: Int, passcode: Long): ULong {
        val body = MccJson.encodeToString(OnNetworkCommissionRequest(inUse = true, passcode, ipAddress, port))

        return decode<CommissionResponse>(perform("/api/companion/nodes", "POST", body, 90)).nodeId
    }

    suspend fun rename(nodeId: ULong, name: String) {
        perform("/api/companion/nodes/$nodeId/update", "PUT", MccJson.encodeToString(RenameRequest(name)), 20)
    }

    suspend fun unpair(nodeId: ULong) {
        perform("/api/companion/nodes/$nodeId", "DELETE", null, 20)
    }

    /** Tells the controller where to send notifications and how to sign in to the relay. */
    suspend fun setNotificationTarget(target: NotificationTarget) {
        perform("/api/companion/notifications", "PUT", MccJson.encodeToString(target), 20)
    }

    /** Stops the controller sending notifications to this phone. */
    suspend fun clearNotificationTarget() {
        perform("/api/companion/notifications", "DELETE", null, 20)
    }

    // endregion

    // region Transport

    /**
     * Built by string so it matches the iOS app exactly. Trims a trailing slash off the base and
     * requires `path` to supply its own leading slash, so a controller address saved with a
     * trailing slash (e.g. `http://host/`) can't produce a double slash that a server's routing
     * treats as an unmatched path.
     */
    internal fun url(path: String): HttpUrl? = (controller.url.removeSuffix("/") + path).toHttpUrlOrNull()

    /** Sends a single request to the controller's configured URL. */
    private suspend fun perform(path: String, method: String, body: String?, timeoutSeconds: Long): String {
        val url = url(path) ?: throw ClientError.Unreachable(controller.url, null)

        Log.d(TAG, "$method $url")

        val request = Request.Builder()
            .url(url)
            .method(method, body?.toRequestBody(jsonMediaType))
            .header("Accept", "application/json")
            .build()

        val result = try {
            http.send(request, timeoutSeconds)
        } catch (e: IOException) {
            throw ClientError.Unreachable(controller.url, e)
        }

        if (result.status !in 200..299) {
            throw ClientError.HttpError(result.status, result.body)
        }

        return result.body
    }

    private inline fun <reified T> decode(body: String): T = try {
        MccJson.decodeFromString<T>(body)
    } catch (e: SerializationException) {
        throw ClientError.DecodingFailed(e)
    } catch (e: IllegalArgumentException) {
        throw ClientError.DecodingFailed(e)
    }

    // endregion
}

sealed class ClientError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class Unreachable(address: String, cause: Throwable?) : ClientError(
        "Couldn't reach $address${cause?.message?.let { " ($it)" } ?: ""}. " +
            "Check that it's powered on and on the same network.",
        cause,
    )

    class HttpError(val status: Int, body: String?) : ClientError(
        if (!body.isNullOrEmpty()) "The controller returned $status: $body" else "The controller returned $status.",
    )

    class DecodingFailed(cause: Throwable) : ClientError("Couldn't read the controller's response.", cause)

    class NoPendingCommissioningTarget : ClientError("No controller was set up to receive this device.")
}

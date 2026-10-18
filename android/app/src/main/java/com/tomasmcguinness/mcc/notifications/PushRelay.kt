package com.tomasmcguinness.mcc.notifications

import com.tomasmcguinness.mcc.BuildConfig
import com.tomasmcguinness.mcc.data.MccJson
import com.tomasmcguinness.mcc.data.PushRequest
import com.tomasmcguinness.mcc.data.send
import com.tomasmcguinness.mcc.data.sharedHttpClient
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The self-hosted relay that holds the FCM (and APNs) credentials and forwards controllers'
 * notifications. See `relay/README.md`.
 */
object PushRelay {

    /** Where controllers (and the test button) send notifications. */
    val baseUrl: String = BuildConfig.PUSH_RELAY_URL

    /** Tells the relay the token is an FCM registration token rather than an APNs one. */
    const val ENVIRONMENT = "fcm"

    /** Sends a notification through the relay exactly the way a controller would. */
    suspend fun send(push: PushRequest, username: String, password: String) {
        val request = Request.Builder()
            .url(baseUrl.removeSuffix("/") + "/v1/push")
            .post(MccJson.encodeToString(push).toRequestBody("application/json".toMediaType()))
            .header("Authorization", Credentials.basic(username, password))
            .build()

        val result = try {
            sharedHttpClient.send(request, 20)
        } catch (e: IOException) {
            throw PushRelayError.Unreachable(e)
        }

        if (result.status !in 200..299) {
            val message = try {
                MccJson.decodeFromString<RelayErrorBody>(result.body).error
            } catch (e: Exception) {
                null
            }

            throw PushRelayError.Rejected(result.status, message)
        }
    }

    @Serializable
    private data class RelayErrorBody(val error: String)
}

sealed class PushRelayError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class Unreachable(cause: Throwable) : PushRelayError("Couldn't reach the push relay (${cause.message}).", cause)

    class Rejected(val status: Int, message: String?) : PushRelayError(
        when {
            status == 401 -> "The push relay didn't accept this controller's username and password."
            !message.isNullOrEmpty() -> "The push relay returned $status: $message"
            else -> "The push relay returned $status."
        },
    )
}
